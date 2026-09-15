import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * Vite 配置
 *
 * 端口约定（见根目录 README.md）：
 *   前端 dev  5173
 *   后端       8080
 *   代理规则   /api/**  ->  http://localhost:8080/api/**
 *
 * 说明：没有配置 SCSS 全局注入（additionalData）。
 * 需要变量时在文件里显式 `@use '@/styles/variables.scss' as *;`，
 * 这样可避免 variables.scss 自我注入导致的 Sass 模块循环错误。
 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const backendTarget = env.VITE_API_TARGET || 'http://localhost:8080'

  return {
    plugins: [vue()],

    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url))
      }
    },

    server: {
      host: '0.0.0.0',
      port: Number(env.VITE_PORT) || 5173,
      open: false,
      proxy: {
        // 后端接口：统一以 /api 开头，直接透传
        '/api': {
          target: backendTarget,
          changeOrigin: true
        },
        // 接口文档（Knife4j），方便开发时直接打开
        '/doc.html': { target: backendTarget, changeOrigin: true },
        '/webjars': { target: backendTarget, changeOrigin: true },
        '/v3/api-docs': { target: backendTarget, changeOrigin: true }
      }
    },

    build: {
      outDir: 'dist',
      sourcemap: mode !== 'production',
      chunkSizeWarningLimit: 1500,
      rollupOptions: {
        output: {
          // 按体积拆分公共依赖，避免单个 chunk 过大
          manualChunks: {
            vue: ['vue', 'vue-router', 'pinia'],
            elementPlus: ['element-plus', '@element-plus/icons-vue']
          }
        }
      }
    }
  }
})
