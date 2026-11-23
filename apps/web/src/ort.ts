// ONNX Runtime Web, configured to load its WebAssembly from this app's own build (no CDN).
// The `?url` import makes Vite emit the .wasm as a hashed asset; the JS glue is already inside the
// `onnxruntime-web/wasm` bundle. Single-threaded, so the app needs no COOP/COEP headers (plan §2).
import * as ort from 'onnxruntime-web/wasm';
import wasmUrl from 'onnxruntime-web/ort-wasm-simd-threaded.wasm?url';

ort.env.wasm.wasmPaths = { wasm: wasmUrl };
ort.env.wasm.numThreads = 1;

export { ort };
