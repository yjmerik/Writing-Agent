import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // 开发时把 /api 请求代理到后端 8080，避免 CORS
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    // 打包输出到 Spring Boot 的 static 目录，同源访问
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
});
