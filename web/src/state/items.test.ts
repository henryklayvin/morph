import { describe, expect, it } from 'vitest';
import contract from '../../contract/capabilities.json';
import type { Capabilities, ConversionResult, SourceFile } from '../backend/types';
import { effectiveOptions, formatSize, type Item, mergeable, operationFor, orphaned, reduce } from './items';

const capabilities = contract as Capabilities;
const source = (id: string, format: string): SourceFile => ({
  id, name: `${id}.${format}`, size: 100, format, warning: null, targets: ['jpeg', 'pdf', 'webp'],
});
const result: ConversionResult = {
  jobId: 'j', operation: 'png-to-webp', provider: 'image', outputs: [], warnings: [], elapsedMillis: 1,
};

function ready(key: string, format = 'png', target = 'webp'): Item[] {
  const adding = reduce([], { type: 'adding', key, label: key, size: 100 });
  return reduce(adding, { type: 'added', key, source: source(key, format), target });
}

describe('items', () => {
  it('go from added to converted', () => {
    let items = ready('a');
    items = reduce(items, { type: 'started', key: 'a' });
    items = reduce(items, { type: 'progress', key: 'a', fraction: 0.6 });
    items = reduce(items, { type: 'progress', key: 'a', fraction: 0.2 });
    expect(items[0]).toMatchObject({ status: 'converting', progress: 0.6 });
    items = reduce(items, { type: 'done', key: 'a', result });
    expect(items[0]).toMatchObject({ status: 'done', progress: 1 });
  });

  it('discard settings and results of the previous target', () => {
    let items = reduce(ready('a'), { type: 'option', key: 'a', option: 'quality', value: '10' });
    items = reduce(items, { type: 'done', key: 'a', result });
    items = reduce(items, { type: 'target', key: 'a', target: 'pdf' });
    expect(items[0]).toMatchObject({ target: 'pdf', options: {}, result: null, status: 'ready' });
  });

  it('ignore progress that arrives after the end', () => {
    let items = reduce(reduce(ready('a'), { type: 'started', key: 'a' }), { type: 'failed', key: 'a', message: 'x' });
    items = reduce(items, { type: 'progress', key: 'a', fraction: 0.9 });
    expect(items[0]).toMatchObject({ status: 'failed', progress: 0, error: 'x' });
  });

  it('find the operation, and none for an unavailable one', () => {
    expect(operationFor(capabilities, ready('a')[0]!)?.id).toBe('png-to-webp');
    const off = { ...capabilities, operations: capabilities.operations.map((o) => ({ ...o, available: false })) };
    expect(operationFor(off, ready('a')[0]!)).toBeUndefined();
  });

  it('can be joined when the engine says so, keeping their order', () => {
    const items = [...ready('a', 'png'), ...ready('b', 'jpeg'), ...ready('c', 'pdf', 'png')];
    expect(mergeable(capabilities, items)).toEqual({
      sources: [source('a', 'png'), source('b', 'jpeg')],
      target: 'pdf',
    });
    expect(mergeable(capabilities, ready('a'))).toBeNull();
  });

  it('free a source only when no other line uses it', () => {
    const single = ready('a');
    const all = reduce(single, { type: 'merged', key: 'm', sources: [source('a', 'png'), source('b', 'png')], target: 'pdf' });
    expect(orphaned(all, all[0]!)).toEqual([]);
    expect(orphaned(all, all[1]!).map((s) => s.id)).toEqual(['b']);
  });

  it('send only settings that differ from the defaults and that the operation knows', () => {
    const specs = capabilities.operations.find((o) => o.id === 'png-to-jpeg')!.options;
    expect(effectiveOptions(specs, { quality: '85', background: '#000000', dpi: '300' })).toEqual({
      background: '#000000',
    });
  });

  it('show sizes in a readable way', () => {
    expect([0, 1023, 1024, 1536, 5 * 1024 * 1024].map(formatSize)).toEqual([
      '0 B', '1023 B', '1 KiB', '1,5 KiB', '5 MiB',
    ]);
  });
});
