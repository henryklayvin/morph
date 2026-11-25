import {
  BackendError,
  type BackendInfo,
  type Capabilities,
  type ConversionBackend,
  type ConversionResult,
  type OptionValues,
  type OutputFile,
  type SourceFile,
} from './types';

const API = '/api/v1';
const TOKEN_KEY = 'morph.token';
const POLL_MILLIS = 120;

interface JobView {
  id: string;
  state: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';
  progress: number;
  result: Omit<ConversionResult, 'jobId'> | null;
  error: { code: string; message: string } | null;
}

/** The parts of the browser this backend touches, replaceable in tests. */
export interface Environment {
  fetch: typeof fetch;
  storage: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;
  /** Returns the launch code of the address bar, if any, and erases it from there. */
  takeLaunchCode(): string | null;
  sleep(millis: number): Promise<void>;
}

export function browserEnvironment(): Environment {
  return {
    fetch: window.fetch.bind(window),
    // Per tab and erased with it; never sent automatically, unlike a cookie.
    storage: window.sessionStorage,
    takeLaunchCode() {
      const match = /^#code=(.+)$/.exec(window.location.hash);
      if (!match) return null;
      window.history.replaceState(null, '', window.location.pathname);
      return match[1] ?? null;
    },
    sleep: (millis) => new Promise((resolve) => setTimeout(resolve, millis)),
  };
}

/** Talks to the Java engine through the loopback server started by `morph web`. */
export class LocalEngineBackend implements ConversionBackend {
  constructor(private readonly env: Environment = browserEnvironment()) {}

  async connect(): Promise<BackendInfo> {
    const code = this.env.takeLaunchCode();
    if (code) {
      const { token } = await this.json<{ token: string }>('POST', '/session', { code });
      this.env.storage.setItem(TOKEN_KEY, token);
    }
    const info = await this.json<{ version: string; javaVersion: string; maxUploadBytes: number }>('GET', '/info');
    return { version: info.version, engine: `Java ${info.javaVersion}`, maxUploadBytes: info.maxUploadBytes };
  }

  capabilities(): Promise<Capabilities> {
    return this.json('GET', '/capabilities');
  }

  async addFile(file: File): Promise<SourceFile> {
    const response = await this.send('POST', '/files', file, {
      'Content-Type': 'application/octet-stream',
      'X-Morph-Filename': encodeURIComponent(file.name),
    });
    return (await response.json()) as SourceFile;
  }

  async removeFile(id: string): Promise<void> {
    await this.send('DELETE', `/files/${encodeURIComponent(id)}`);
  }

  async convert(
    inputs: string[],
    target: string,
    options: OptionValues,
    onProgress: (fraction: number) => void,
    signal: AbortSignal,
  ): Promise<ConversionResult> {
    let job = await this.json<JobView>('POST', '/jobs', { inputs, target, options });
    const path = `/jobs/${encodeURIComponent(job.id)}`;
    let cancelSent = false;
    while (job.state === 'QUEUED' || job.state === 'RUNNING') {
      onProgress(job.progress);
      await this.env.sleep(POLL_MILLIS);
      if (signal.aborted && !cancelSent) {
        cancelSent = true;
        await this.send('POST', `${path}/cancel`);
      }
      job = await this.json<JobView>('GET', path);
    }
    if (job.result) return { ...job.result, jobId: job.id };
    await this.send('DELETE', path);
    throw new BackendError(job.error?.code ?? 'INTERNAL_ERROR', job.error?.message ?? 'A conversão falhou.');
  }

  async download(result: ConversionResult, output: OutputFile): Promise<Blob> {
    const response = await this.send(
      'GET',
      `/jobs/${encodeURIComponent(result.jobId)}/outputs/${output.index}`,
    );
    return response.blob();
  }

  async release(result: ConversionResult): Promise<void> {
    await this.send('DELETE', `/jobs/${encodeURIComponent(result.jobId)}`);
  }

  async shutdown(): Promise<void> {
    await this.send('POST', '/shutdown');
    this.env.storage.removeItem(TOKEN_KEY);
  }

  private async json<T>(method: string, path: string, body?: unknown): Promise<T> {
    const response =
      body === undefined
        ? await this.send(method, path)
        : await this.send(method, path, JSON.stringify(body), { 'Content-Type': 'application/json' });
    return (await response.json()) as T;
  }

  private async send(
    method: string,
    path: string,
    body?: BodyInit,
    headers: Record<string, string> = {},
  ): Promise<Response> {
    const token = this.env.storage.getItem(TOKEN_KEY);
    let response: Response;
    try {
      response = await this.env.fetch(API + path, {
        method,
        headers: token ? { ...headers, Authorization: `Bearer ${token}` } : headers,
        // Same origin only, no cookies, no cache: there is nothing else this page should ever talk to.
        mode: 'same-origin',
        credentials: 'omit',
        cache: 'no-store',
        ...(body === undefined ? {} : { body }),
      });
    } catch {
      throw new BackendError('UNREACHABLE', 'O Morph não está mais em execução neste computador.');
    }
    if (!response.ok) throw await toError(response);
    return response;
  }
}

async function toError(response: Response): Promise<BackendError> {
  try {
    const { error } = (await response.json()) as { error: { code: string; message: string } };
    return new BackendError(error.code, error.message);
  } catch {
    return new BackendError(`HTTP_${response.status}`, 'O Morph respondeu de forma inesperada.');
  }
}
