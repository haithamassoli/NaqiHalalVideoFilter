// ONNX Runtime Web, configured to load its WebAssembly from this app's own build (no CDN).
// The `?url` import makes Vite emit the .wasm as a hashed asset; the JS glue is already inside the
// `onnxruntime-web/wasm` bundle. Single-threaded, so the app needs no COOP/COEP headers (plan §2).
import * as ort from 'onnxruntime-web/wasm';
import wasmUrl from 'onnxruntime-web/ort-wasm-simd-threaded.wasm?url';

ort.env.wasm.wasmPaths = { wasm: wasmUrl };
ort.env.wasm.numThreads = 1;

export { ort };

/**
 * Opens a model staged in public/models (scripts/fetch-models.sh) on WebAssembly. A missing or unloadable
 * model throws a message that names it: analysis never goes on without one.
 */
export async function openModel(file: string, what: string): Promise<ort.InferenceSession> {
  const url = new URL(`${import.meta.env.BASE_URL}models/${file}`, self.location.href).href;
  const response = await fetch(url).catch(() => null);
  // A dev server answers a missing file with the HTML app page.
  if (!response?.ok || response.headers.get('content-type')?.includes('text/html')) {
    throw new Error(`The ${what} model (${file}) is missing: run scripts/fetch-models.sh.`);
  }
  try {
    return await ort.InferenceSession.create(new Uint8Array(await response.arrayBuffer()), {
      executionProviders: ['wasm'],
      graphOptimizationLevel: 'all',
    });
  } catch (err) {
    throw new Error(`The ${what} model (${file}) failed to load: ${err instanceof Error ? err.message : String(err)}`);
  }
}

/** Runs one float input through `session` and returns the named outputs' data; a missing output throws. */
export async function runModel(
  session: ort.InferenceSession,
  input: Float32Array,
  shape: number[],
  outputs: readonly string[] = session.outputNames.slice(0, 1),
): Promise<Record<string, Float32Array>> {
  const result = await session.run({ [session.inputNames[0]]: new ort.Tensor('float32', input, shape) }, [...outputs]);
  const out: Record<string, Float32Array> = {};
  for (const name of outputs) {
    const t = result[name];
    if (!t || !(t.data instanceof Float32Array)) throw new Error(`A model returned no float output "${name}".`);
    out[name] = t.data;
  }
  return out;
}
