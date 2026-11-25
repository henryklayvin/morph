import { useCallback, useEffect, useReducer, useRef, useState } from 'react';
import { BackendError, type BackendInfo, type Capabilities, type ConversionBackend } from '../backend/types';
import { effectiveOptions, type Item, mergeable, operationFor, orphaned, reduce } from './items';

export type Phase = 'connecting' | 'ready' | 'refused' | 'ended';

let sequence = 0;
const nextKey = () => `item-${++sequence}`;

/** All the behaviour of the screen, independent of how it looks and of which backend converts. */
export function useMorph(backend: ConversionBackend) {
  const [phase, setPhase] = useState<Phase>('connecting');
  const [reason, setReason] = useState('');
  const [info, setInfo] = useState<BackendInfo | null>(null);
  const [capabilities, setCapabilities] = useState<Capabilities | null>(null);
  const [items, dispatch] = useReducer(reduce, []);
  const running = useRef(new Map<string, AbortController>());
  const connection = useRef<Promise<void> | null>(null);

  useEffect(() => {
    // The launch code works once, and effects may run twice in development.
    connection.current ??= (async () => {
      try {
        setInfo(await backend.connect());
        setCapabilities(await backend.capabilities());
        setPhase('ready');
      } catch (e) {
        setReason(messageOf(e));
        setPhase('refused');
      }
    })();
  }, [backend]);

  const add = useCallback(
    async (files: Iterable<File>) => {
      await Promise.all(
        Array.from(files).map(async (file) => {
          const key = nextKey();
          dispatch({ type: 'adding', key, label: file.name, size: file.size });
          try {
            if (info && file.size > info.maxUploadBytes) {
              throw new BackendError('RESOURCE_LIMIT_EXCEEDED', 'O arquivo é maior que o limite aceito.');
            }
            const source = await backend.addFile(file);
            dispatch({ type: 'added', key, source, target: source.targets[0] ?? null });
          } catch (e) {
            dispatch({ type: 'failed', key, message: messageOf(e) });
          }
        }),
      );
    },
    [backend, info],
  );

  const convert = useCallback(
    async (item: Item) => {
      if (!capabilities || !item.target) return;
      const abort = new AbortController();
      running.current.set(item.key, abort);
      dispatch({ type: 'started', key: item.key });
      try {
        const result = await backend.convert(
          item.sources.map((s) => s.id),
          item.target,
          effectiveOptions(operationFor(capabilities, item)?.options ?? [], item.options),
          (fraction) => dispatch({ type: 'progress', key: item.key, fraction }),
          abort.signal,
        );
        dispatch({ type: 'done', key: item.key, result });
      } catch (e) {
        if (e instanceof BackendError && e.code === 'CANCELLED') dispatch({ type: 'cancelled', key: item.key });
        else dispatch({ type: 'failed', key: item.key, message: messageOf(e) });
      } finally {
        running.current.delete(item.key);
      }
    },
    [backend, capabilities],
  );

  const cancel = useCallback((item: Item) => running.current.get(item.key)?.abort(), []);

  const remove = useCallback(
    async (item: Item) => {
      running.current.get(item.key)?.abort();
      dispatch({ type: 'removed', key: item.key });
      const leftovers = orphaned(items, item).map((source) => backend.removeFile(source.id));
      if (item.result) leftovers.push(backend.release(item.result));
      await Promise.allSettled(leftovers);
    },
    [backend, items],
  );

  const download = useCallback(
    async (item: Item) => {
      if (!item.result) return;
      for (const output of item.result.outputs) {
        try {
          save(await backend.download(item.result, output), output.name);
        } catch (e) {
          dispatch({ type: 'failed', key: item.key, message: messageOf(e) });
          return;
        }
      }
    },
    [backend],
  );

  const merge = useCallback(() => {
    const join = capabilities && mergeable(capabilities, items);
    if (join) dispatch({ type: 'merged', key: nextKey(), ...join });
  }, [capabilities, items]);

  const end = useCallback(async () => {
    await backend.shutdown().catch(() => undefined);
    setPhase('ended');
  }, [backend]);

  return { phase, reason, info, capabilities, items, dispatch, add, convert, cancel, remove, download, merge, end };
}

function messageOf(e: unknown): string {
  return e instanceof BackendError ? e.message : 'Algo deu errado.';
}

function save(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  link.click();
  // Frees the memory once the browser has had time to start the download.
  setTimeout(() => URL.revokeObjectURL(url), 30_000);
}
