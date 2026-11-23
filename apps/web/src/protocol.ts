// Messages between the UI (main.ts) and the pipeline worker (worker.ts).
import type { FilterOps } from './core/filterOps';

/** Where the output goes: a file the user picked (streamed to disk) or an in-memory buffer. */
export type SaveTarget = { kind: 'file'; handle: FileSystemFileHandle } | { kind: 'buffer' };

export type ToWorker =
  | { type: 'start'; file: File; ops: FilterOps; target: SaveTarget }
  | { type: 'cancel' };

export type Phase = 'open' | 'remux' | 'finalize';

export type FromWorker =
  | { type: 'progress'; phase: Phase; pct: number }
  /** `buffer` is set only for a `buffer` target. */
  | { type: 'done'; buffer?: ArrayBuffer }
  | { type: 'error'; message: string; canceled?: boolean };
