import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'
import {fileURLToPath, URL} from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      // 开发时代理 /api 到 Spring Boot
      '/api': {
        target: 'http://localhost:3040',
        changeOrigin: true,
      },
      // 代理 Fuseki 原生接口（/travel_kg_v4 等）
      '/travel_kg_v4': {
        target: 'http://localhost:3030',
        changeOrigin: true,
      },
    },
  },
})

