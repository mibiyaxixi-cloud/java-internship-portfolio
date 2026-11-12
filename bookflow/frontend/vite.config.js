import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 浏览器统一访问 /api，开发时转给真实后端，避免放宽后端 CORS。
export default defineConfig({
  plugins: [vue()],
  server: {
    host: '127.0.0.1', port: 5173, strictPort: true,
    proxy: { '/api': { target: process.env.API_PROXY_TARGET || 'http://127.0.0.1:8080' } },
  },
  preview: {
    host: '127.0.0.1', port: 4173, strictPort: true,
    proxy: { '/api': { target: process.env.API_PROXY_TARGET || 'http://127.0.0.1:8080' } },
  },
})
