// UI only: collect options, hand the file to the worker, show progress, save the result.
import { BLUR, DEFAULT_OPS, DEFAULT_WHO, SOLID_COLORS, type CensorWho, type FilterOps } from './core/filterOps';
import type { FromWorker, Phase, SaveTarget, ToWorker } from './protocol';

/** The in-memory fallback holds the whole output in RAM; refuse inputs that would not fit. */
const BUFFER_CAP_BYTES = 2 * 1024 ** 3;

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;
const form = $<HTMLFormElement>('form');
const fileIn = $<HTMLInputElement>('file');
const removeMusic = $<HTMLInputElement>('removeMusic');
const censorWho = $<HTMLSelectElement>('censorWho');
const censorNsfw = $<HTMLInputElement>('censorNsfw');
const strictness = $<HTMLInputElement>('strictness');
const bodyBlur = $<HTMLInputElement>('bodyBlur');
const effect = $<HTMLSelectElement>('effect');
const amount = $<HTMLInputElement>('amount');
const startBtn = $<HTMLButtonElement>('start');
const cancelBtn = $<HTMLButtonElement>('cancel');
const status = $<HTMLElement>('status');
const bar = $<HTMLProgressElement>('bar');
const phaseText = $<HTMLElement>('phase');
const saveLink = $<HTMLAnchorElement>('save');

// Entry-point defaults: face censoring opens on DEFAULT_WHO, like the apps (FilterOps.kt).
removeMusic.checked = DEFAULT_OPS.removeMusic;
censorWho.value = DEFAULT_WHO;
censorNsfw.checked = DEFAULT_OPS.censorNsfw;
strictness.valueAsNumber = DEFAULT_OPS.strictness;
bodyBlur.checked = DEFAULT_OPS.bodyBlur;
amount.valueAsNumber = DEFAULT_OPS.blurAmount;

const syncOutputs = () => {
  $('strictnessOut').textContent = strictness.value;
  $('amountOut').textContent = amount.value;
  strictness.disabled = !censorNsfw.checked;
  amount.disabled = effect.value === 'solid';
};
form.addEventListener('input', syncOutputs);
syncOutputs();

function readOps(): FilterOps {
  const style = effect.value;
  return {
    ...DEFAULT_OPS,
    removeMusic: removeMusic.checked,
    censorWho: censorWho.value as CensorWho,
    censorNsfw: censorNsfw.checked,
    strictness: strictness.valueAsNumber,
    bodyBlur: bodyBlur.checked,
    blurAmount: amount.valueAsNumber,
    grayscale: style === 'grayscale',
    solidColor: style === 'solid' ? SOLID_COLORS[0] : BLUR,
  };
}

const PHASES: Record<Phase, string> = {
  open: 'Opening the video…',
  analyze: 'Analyzing the picture…',
  remux: 'Writing the MP4…',
  model: 'Loading the music model…',
  music: 'Removing music…',
  finalize: 'Finishing…',
};

const useFilePicker = () =>
  typeof window.showSaveFilePicker === 'function' && !new URLSearchParams(location.search).has('e2e');

let worker: Worker | null = null;

function setBusy(busy: boolean) {
  startBtn.disabled = busy;
  cancelBtn.hidden = !busy;
  for (const el of form.querySelectorAll('input, select')) (el as HTMLInputElement).disabled = busy;
  if (!busy) syncOutputs();
}

function outputName(file: File, mimeType = 'video/mp4') {
  return `${file.name.replace(/\.[^.]+$/, '')}-naqi.${mimeType === 'video/webm' ? 'webm' : 'mp4'}`;
}

form.addEventListener('submit', async (e) => {
  e.preventDefault();
  const file = fileIn.files?.[0];
  if (!file || worker) return;

  // The save picker needs this click's user activation, so it opens before any other await.
  let target: SaveTarget;
  if (useFilePicker()) {
    try {
      const handle = await window.showSaveFilePicker!({
        suggestedName: outputName(file),
        types: [{ description: 'MP4 video', accept: { 'video/mp4': ['.mp4'] } }],
      });
      target = { kind: 'file', handle };
    } catch {
      return; // picker dismissed
    }
  } else {
    if (file.size > BUFFER_CAP_BYTES) {
      show('This browser cannot save straight to disk, and the file is too large to hold in memory. Use desktop Chrome or Edge.');
      return;
    }
    target = { kind: 'buffer' };
  }

  if (saveLink.href) URL.revokeObjectURL(saveLink.href);
  saveLink.hidden = true;
  saveLink.removeAttribute('href');
  bar.value = 0;
  show(PHASES.open);
  setBusy(true);

  const w = new Worker(new URL('./worker.ts', import.meta.url), { type: 'module' });
  worker = w;
  let notice = '';
  w.onmessage = (ev: MessageEvent<FromWorker>) => {
    const msg = ev.data;
    if (msg.type === 'notice') {
      notice = ` ${msg.message}`;
      return;
    }
    if (msg.type === 'progress') {
      bar.value = msg.pct;
      show(`${PHASES[msg.phase]} ${Math.round(msg.pct * 100)}%`);
      return;
    }
    finish();
    if (msg.type === 'error') {
      show(msg.canceled ? 'Canceled. Nothing was saved.' : `Failed: ${msg.message} Nothing was saved.`);
    } else if (msg.buffer) {
      bar.value = 1;
      saveLink.href = URL.createObjectURL(new Blob([msg.buffer], { type: msg.mimeType }));
      saveLink.download = outputName(file, msg.mimeType);
      saveLink.hidden = false;
      show(`Done.${notice} Save the video:`);
    } else {
      bar.value = 1;
      show(`Done. Saved as ${target.kind === 'file' ? target.handle.name : outputName(file)}.${notice}`);
    }
  };
  w.onerror = (ev) => {
    finish();
    show(`Failed: ${ev.message || 'the worker crashed'}. Nothing was saved.`);
  };
  w.postMessage({ type: 'start', file, ops: readOps(), target } satisfies ToWorker);
});

cancelBtn.addEventListener('click', () => {
  worker?.postMessage({ type: 'cancel' } satisfies ToWorker);
  show('Canceling…');
});

function finish() {
  worker?.terminate();
  worker = null;
  setBusy(false);
}

function show(text: string) {
  status.hidden = false;
  phaseText.textContent = text;
}
