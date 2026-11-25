import { describe, expect, it } from 'vitest';
import contract from '../../contract/capabilities.json';
import type { Capabilities, Operation, OptionSpec } from './types';

/** The file is written by the Java tests (ApiContractTest). Here we check that this side can live with it. */
const { formats, operations } = contract as Capabilities;

describe('contract shared with the Java engine', () => {
  it('uses the same stable operation ids everywhere', () => {
    for (const operation of operations) expect(operation.id).toBe(`${operation.source}-to-${operation.target}`);
    expect(new Set(operations.map((o) => o.id)).size).toBe(operations.length);
  });

  it('only refers to declared formats', () => {
    const ids = new Set(formats.map((f) => f.id));
    for (const { source, target } of operations) expect(ids.has(source) && ids.has(target)).toBe(true);
  });

  it('has exactly the fields the interface declares', () => {
    const operationFields: Record<keyof Operation, true> = {
      id: true, source: true, target: true, provider: true, available: true, reason: true,
      cardinality: true, options: true, losses: true, limitations: true,
    };
    for (const o of operations) expect(Object.keys(o).sort()).toEqual(Object.keys(operationFields).sort());
    expect(Object.keys(formats[0]!).sort()).toEqual(['category', 'extensions', 'id', 'mediaType', 'name']);
  });

  it('only uses option types and cardinalities the interface can show', () => {
    const known: Record<OptionSpec['type'], (o: OptionSpec) => boolean> = {
      integer: (o) => o.type === 'integer' && o.min <= o.default && o.default <= o.max,
      boolean: (o) => typeof o.default === 'boolean',
      choice: (o) => o.type === 'choice' && o.choices.includes(o.default),
      color: (o) => /^#[0-9a-f]{6}$/.test(String(o.default)),
    };
    for (const option of operations.flatMap((o) => o.options)) expect(known[option.type](option)).toBe(true);
    for (const o of operations) expect(['ONE_TO_ONE', 'ONE_TO_MANY', 'MANY_TO_ONE']).toContain(o.cardinality);
  });
});
