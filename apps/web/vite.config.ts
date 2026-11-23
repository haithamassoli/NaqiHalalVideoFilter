import { defineConfig } from 'vitest/config';

export default defineConfig({
  // Prebundling would rewrite onnxruntime-web's own `new URL(..., import.meta.url)`; src/ort.ts sets the
  // .wasm URL explicitly anyway, so keep the package as shipped.
  optimizeDeps: { exclude: ['onnxruntime-web'] },
  worker: { format: 'es' },
  build: { target: 'es2023' },
  test: { include: ['test/**/*.test.ts'], environment: 'node' },
});
