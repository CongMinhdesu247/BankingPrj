import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const target = env.API_PROXY_TARGET || 'http://127.0.0.1:8080';
  return { plugins: [react()], server: { host: '127.0.0.1', port: 5173, strictPort: true,
    proxy: Object.fromEntries(['/api', '/v3/api-docs', '/swagger-ui'].map(path => [path, { target, changeOrigin: true }])) } };
});
