/**
 * Contract between the interface and whoever performs the conversions. Mirrors
 * io.github.henryklayvin.morph.local.ApiViews; the file contract/capabilities.json is produced by the Java tests and
 * read by the TypeScript ones, so that neither side can drift unnoticed. Operation ids and format ids are the same in
 * the CLI, Morph Local and the public Web build.
 */

export type Cardinality = 'ONE_TO_ONE' | 'ONE_TO_MANY' | 'MANY_TO_ONE';

export interface Format {
  id: string;
  name: string;
  category: string;
  extensions: string[];
  mediaType: string;
}

interface OptionBase {
  key: string;
  description: string;
}

export type OptionSpec =
  | (OptionBase & { type: 'integer'; default: number; min: number; max: number })
  | (OptionBase & { type: 'boolean'; default: boolean })
  | (OptionBase & { type: 'choice'; default: string; choices: string[] })
  | (OptionBase & { type: 'color'; default: string });

export interface Loss {
  kind: string;
  description: string;
}

export interface Operation {
  id: string;
  source: string;
  target: string;
  provider: string;
  available: boolean;
  reason: string;
  cardinality: Cardinality;
  options: OptionSpec[];
  losses: Loss[];
  limitations: string[];
}

export interface Capabilities {
  formats: Format[];
  operations: Operation[];
}

/** A file already handed to the backend and identified by its content. */
export interface SourceFile {
  id: string;
  name: string;
  size: number;
  format: string;
  warning: string | null;
  targets: string[];
}

export interface OutputFile {
  index: number;
  name: string;
  size: number;
  format: string;
  properties: Record<string, string>;
}

export interface ConversionResult {
  jobId: string;
  operation: string;
  provider: string;
  outputs: OutputFile[];
  warnings: string[];
  elapsedMillis: number;
}

export interface BackendInfo {
  version: string;
  /** Human description of where the work happens. */
  engine: string;
  maxUploadBytes: number;
}

export class BackendError extends Error {
  constructor(
    readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = 'BackendError';
  }
}

export type OptionValues = Record<string, string>;

/** Implemented by the local engine today and by the in-browser engine in phase 3. */
export interface ConversionBackend {
  connect(): Promise<BackendInfo>;
  capabilities(): Promise<Capabilities>;
  addFile(file: File): Promise<SourceFile>;
  removeFile(id: string): Promise<void>;
  /** Resolves with a verified result; rejects with BackendError, code CANCELLED when aborted. */
  convert(
    inputs: string[],
    target: string,
    options: OptionValues,
    onProgress: (fraction: number) => void,
    signal: AbortSignal,
  ): Promise<ConversionResult>;
  download(result: ConversionResult, output: OutputFile): Promise<Blob>;
  /** Erases the outputs of a conversion. */
  release(result: ConversionResult): Promise<void>;
  /** Ends the backend, when it is a separate process. */
  shutdown(): Promise<void>;
}
