import { describe, expect, it } from 'vitest';
import { type Environment, LocalEngineBackend } from './localEngine';
import { BackendError } from './types';

interface Call {
  url: string;
  init: RequestInit;
}

function setup(replies: Array<Response | Error>, launchCode: string | null = null) {
  const calls: Call[] = [];
  const stored = new Map<string, string>();
  const env: Environment = {
    fetch: async (url, init) => {
      calls.push({ url: String(url), init: init ?? {} });
      const reply = replies.shift();
      if (!reply) throw new Error('unexpected request');
      if (reply instanceof Error) throw reply;
      return reply;
    },
    storage: {
      getItem: (key) => stored.get(key) ?? null,
      setItem: (key, value) => void stored.set(key, value),
      removeItem: (key) => void stored.delete(key),
    },
    takeLaunchCode: () => launchCode,
    sleep: async () => undefined,
  };
  return { backend: new LocalEngineBackend(env), calls, stored };
}

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
const info = { version: '1', javaVersion: '21', maxUploadBytes: 10 };
const headers = (call: Call | undefined) => new Headers(call?.init.headers);

describe('LocalEngineBackend', () => {
  it('exchanges the launch code once and uses the token from then on', async () => {
    const { backend, calls, stored } = setup([json({ token: 'secret' }), json(info)], 'one-time');
    await backend.connect();
    expect(JSON.parse(String(calls[0]?.init.body))).toEqual({ code: 'one-time' });
    expect(headers(calls[0]).has('Authorization')).toBe(false);
    expect(headers(calls[1]).get('Authorization')).toBe('Bearer secret');
    expect([...stored.values()]).toEqual(['secret']);
  });

  it('only ever calls its own origin, without cookies', async () => {
    const { backend, calls } = setup([json(info), json({ formats: [], operations: [] })]);
    await backend.connect();
    await backend.capabilities();
    for (const call of calls) {
      expect(call.url.startsWith('/api/v1/')).toBe(true);
      expect(call.init.mode).toBe('same-origin');
      expect(call.init.credentials).toBe('omit');
    }
  });

  it('sends files as opaque bytes with an encoded name', async () => {
    const { backend, calls } = setup([json({ id: 'f' }, 201)]);
    await backend.addFile(new File(['x'], 'férias & praia.png'));
    expect(headers(calls[0]).get('Content-Type')).toBe('application/octet-stream');
    expect(headers(calls[0]).get('X-Morph-Filename')).toBe('f%C3%A9rias%20%26%20praia.png');
  });

  it('follows a job until its result', async () => {
    const result = { operation: 'a-to-b', provider: 'p', outputs: [], warnings: [], elapsedMillis: 5 };
    const { backend } = setup([
      json({ id: 'j', state: 'QUEUED', progress: 0 }, 202),
      json({ id: 'j', state: 'RUNNING', progress: 0.5 }),
      json({ id: 'j', state: 'SUCCEEDED', progress: 1, result }),
    ]);
    const seen: number[] = [];
    const done = await backend.convert(['f'], 'b', {}, (p) => seen.push(p), new AbortController().signal);
    expect(done).toEqual({ ...result, jobId: 'j' });
    expect(seen).toEqual([0, 0.5]);
  });

  it('cancels once and reports the cancellation', async () => {
    const { backend, calls } = setup([
      json({ id: 'j', state: 'RUNNING', progress: 0 }, 202),
      json({}),
      json({ id: 'j', state: 'RUNNING', progress: 0 }),
      json({ id: 'j', state: 'CANCELLED', progress: 0, result: null, error: { code: 'CANCELLED', message: 'x' } }),
      new Response(null, { status: 204 }),
    ]);
    const abort = new AbortController();
    abort.abort();
    await expect(backend.convert(['f'], 'b', {}, () => undefined, abort.signal)).rejects.toMatchObject({
      code: 'CANCELLED',
    });
    expect(calls.filter((c) => c.url.endsWith('/cancel'))).toHaveLength(1);
  });

  it('turns every failure into a BackendError with a readable message', async () => {
    const refused = setup([json({ error: { code: 'UNAUTHORIZED', message: 'Sessão inválida.' } }, 401)]);
    await expect(refused.backend.connect()).rejects.toMatchObject({ code: 'UNAUTHORIZED', message: 'Sessão inválida.' });
    const gone = setup([new TypeError('Failed to fetch')]);
    await expect(gone.backend.connect()).rejects.toMatchObject({ code: 'UNREACHABLE' });
    const odd = setup([new Response('<html>', { status: 502 })]);
    await expect(odd.backend.connect()).rejects.toBeInstanceOf(BackendError);
  });
});
