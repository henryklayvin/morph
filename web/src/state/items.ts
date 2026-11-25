import type {
  Capabilities,
  ConversionResult,
  Operation,
  OptionSpec,
  OptionValues,
  SourceFile,
} from '../backend/types';

export type Status = 'adding' | 'ready' | 'converting' | 'done' | 'failed';

/** One line of the list. A merge is an item with several sources. */
export interface Item {
  key: string;
  label: string;
  size: number;
  status: Status;
  sources: SourceFile[];
  target: string | null;
  options: OptionValues;
  progress: number;
  result: ConversionResult | null;
  error: string | null;
}

export type Action =
  | { type: 'adding'; key: string; label: string; size: number }
  | { type: 'added'; key: string; source: SourceFile; target: string | null }
  | { type: 'merged'; key: string; sources: SourceFile[]; target: string }
  | { type: 'target'; key: string; target: string }
  | { type: 'option'; key: string; option: string; value: string }
  | { type: 'started'; key: string }
  | { type: 'progress'; key: string; fraction: number }
  | { type: 'done'; key: string; result: ConversionResult }
  | { type: 'failed'; key: string; message: string }
  | { type: 'cancelled'; key: string }
  | { type: 'removed'; key: string };

export function reduce(items: Item[], action: Action): Item[] {
  switch (action.type) {
    case 'adding':
      return [...items, blank(action.key, action.label, action.size)];
    case 'added':
      return patch(items, action.key, {
        status: 'ready',
        sources: [action.source],
        label: action.source.name,
        target: action.target,
      });
    case 'merged':
      return [
        ...items,
        {
          ...blank(action.key, `${action.sources.length} imagens em um arquivo`, total(action.sources)),
          status: 'ready',
          sources: action.sources,
          target: action.target,
        },
      ];
    case 'target':
      // Settings belong to an operation; keeping them across targets would send keys the new one does not know.
      return patch(items, action.key, { target: action.target, options: {}, status: 'ready', result: null, error: null });
    case 'option':
      return items.map((item) =>
        item.key === action.key
          ? { ...item, options: { ...item.options, [action.option]: action.value }, status: 'ready', result: null }
          : item,
      );
    case 'started':
      return patch(items, action.key, { status: 'converting', progress: 0, result: null, error: null });
    case 'progress':
      return items.map((item) =>
        item.key === action.key && item.status === 'converting'
          ? { ...item, progress: Math.max(item.progress, action.fraction) }
          : item,
      );
    case 'done':
      return patch(items, action.key, { status: 'done', progress: 1, result: action.result });
    case 'failed':
      return patch(items, action.key, { status: 'failed', error: action.message });
    case 'cancelled':
      return patch(items, action.key, { status: 'ready', progress: 0 });
    case 'removed':
      return items.filter((item) => item.key !== action.key);
  }
}

export function operationFor(capabilities: Capabilities, item: Item): Operation | undefined {
  const source = item.sources[0]?.format;
  return capabilities.operations.find((o) => o.available && o.source === source && o.target === item.target);
}

/** Files that can be joined, in list order, and the format they can be joined into. */
export function mergeable(capabilities: Capabilities, items: Item[]): { sources: SourceFile[]; target: string } | null {
  const joins = capabilities.operations.filter((o) => o.available && o.cardinality === 'MANY_TO_ONE');
  const target = joins[0]?.target;
  if (!target) return null;
  const accepted = new Set(joins.filter((o) => o.target === target).map((o) => o.source));
  const sources = items
    .filter((item) => item.sources.length === 1 && item.status !== 'converting')
    .map((item) => item.sources[0])
    .filter((source): source is SourceFile => source !== undefined && accepted.has(source.format));
  return sources.length > 1 ? { sources, target } : null;
}

/** Only what differs from the default is sent, so that the backend stays the owner of its defaults. */
export function effectiveOptions(specs: OptionSpec[], chosen: OptionValues): OptionValues {
  const values: OptionValues = {};
  for (const spec of specs) {
    const value = chosen[spec.key];
    if (value !== undefined && value !== String(spec.default)) values[spec.key] = value;
  }
  return values;
}

/** Sources no other line still needs. */
export function orphaned(items: Item[], removed: Item): SourceFile[] {
  const used = new Set(
    items.filter((item) => item.key !== removed.key).flatMap((item) => item.sources.map((s) => s.id)),
  );
  return removed.sources.filter((source) => !used.has(source.id));
}

export function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const units = ['KiB', 'MiB', 'GiB'];
  let value = bytes;
  let unit = -1;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value.toLocaleString('pt-BR', { maximumFractionDigits: 1 })} ${units[unit]}`;
}

function blank(key: string, label: string, size: number): Item {
  return {
    key,
    label,
    size,
    status: 'adding',
    sources: [],
    target: null,
    options: {},
    progress: 0,
    result: null,
    error: null,
  };
}

function patch(items: Item[], key: string, change: Partial<Item>): Item[] {
  return items.map((item) => (item.key === key ? { ...item, ...change } : item));
}

function total(sources: SourceFile[]): number {
  return sources.reduce((sum, source) => sum + source.size, 0);
}
