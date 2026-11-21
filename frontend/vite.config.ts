import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In dev the UI runs on :5173 and proxies the API and the STOMP WebSocket to the backend (:8080).
// In the Docker image the build output is served by the backend itself (same origin).
const backend = process.env.BACKEND_URL ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: backend, changeOrigin: false },
      '/ws': { target: backend.replace(/^http/, 'ws'), ws: true },
    },
  },
  build: { outDir: 'dist', sourcemap: false, chunkSizeWarningLimit: 900 },
});
