// Messages between the UI (main.ts) and the pipeline worker (worker.ts).
import type { FilterOps } from './core/filterOps';

/** Where the output goes: a file the user picked (streamed to disk) or an in-memory buffer. */
export type SaveTarget = { kind: 'file'; handle: FileSystemFileHandle } | { kind: 'buffer' };

export type ToWorker =
  | { type: 'start'; file: File; ops: FilterOps; target: SaveTarget }
  | { type: 'cancel' };

export type Phase = 'open' | 'analyze' | 'model' | 'music' | 'render' | 'finalize';

export type FromWorker =
  /** `pct` 0..1 within `phase`; `eta` seconds left in it, once it can be estimated. */
  | { type: 'progress'; phase: Phase; pct: number; eta?: number }
  /** Something the user should know about the output, e.g. a codec fallback. */
  | { type: 'notice'; message: string }
  /** `buffer` is set only for a `buffer` target; `mimeType` is the output container's. */
  | { type: 'done'; buffer?: ArrayBuffer; mimeType: string }
  | { type: 'error'; message: string; canceled?: boolean };
