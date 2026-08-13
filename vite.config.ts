import path from 'node:path'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import svgr from 'vite-plugin-svgr'

// 推荐直接使用 Node.js 原生的 import.meta.dirname
const currentDir = import.meta.dirname

export default defineConfig(({ command }) => {
  const isServe = command === 'serve'

  return {
    // 2. 将原有的 __dirname 替换为 import.meta.dirname (即 currentDir)
    root: path.resolve(currentDir, 'src/frontend'),

    resolve: {
      alias: {
        '@': path.resolve(currentDir, 'src/frontend'),
      },
    },

    server: {
      host: '127.0.0.1',
      port: 15173,
      watch: {
        ignored: ['**/node_modules/**', '**/dist/**']
      }
    },

    build: {
      outDir: path.resolve(currentDir, 'dist'),
      emptyOutDir: true,

      rollupOptions: {
        external: ['web-worker'],
        output: {
          manualChunks(id) {
            if (id.includes('node_modules')) {
              if (id.includes('@monaco-editor')) return 'monaco-vendor'
              if (id.includes('@langchain')) return 'langchain-vendor'
              if (id.includes('@blueprintjs')) return 'blueprint-vendor'
              if (id.includes('@xyflow')) return 'xyflow-vendor'
              if (id.includes('lucide-react')) return 'lucide-vendor'
              if (id.includes('quill')) return 'quill-vendor'
              if (id.includes('react') || id.includes('react-dom') || id.includes('react-router')) return 'react-vendor'
              return 'vendor'
            }
          }
        }
      }
    },

    plugins: [
      react(),
      svgr(),
    ],
    clearScreen: false,
  }
})