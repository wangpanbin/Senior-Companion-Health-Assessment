import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

/// <reference types="vitest/config" />

/**
 * Vite 配置
 *
 * 端口约定（见根目录 README.md）：
 *   前端 dev  5141（原 5173 落在 Windows Hyper-V 排除端口段 5152-5251 内，会 EACCES）
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
      port: Number(env.VITE_PORT) || 5141,
      open: false,
      // ⚠️ Windows 下必须忽略「原子写临时目录」。
      //    编辑器 / 脚本 / 代码生成工具落盘时常走
      //    `.<文件名>.<pid>.<uuid>.tmpdir/<文件名>.tmp` 再 rename 的路子（避免写一半被读到）。
      //    chokidar 会去 watch 这个刚建又刚删的临时文件，在 Windows 上拿到 EBUSY
      //    （文件已被 rename 掉 / 被杀软占着），而 Vite 的 FSWatcher 对这个错误
      //    没有兜底 —— 以未捕获的 'error' 事件直接退出进程。
      //    症状很误导：dev server 无任何日志地消失，浏览器只报 ERR_CONNECTION_REFUSED，
      //    看起来像「端口被占」或「前端崩了」，实际只是保存了一次文件。
      watch: {
        ignored: ['**/*.tmpdir/**']
      },
      proxy: {
        // 后端接口：统一以 /api 开头，直接透传
        '/api': {
          target: backendTarget,
          changeOrigin: true
        },
        // ⚠️ 陪诊进度走 WebSocket（端点 /ws/progress?token=&orderId=），同样不在 /api 下。
        //    ws:true 是必须的 —— 少了它 vite 不会做协议升级，前端拿到的是一次普通 HTTP
        //    响应，WebSocket 构造函数直接报 "Unexpected response code: 200"。
        '/ws': {
          target: backendTarget,
          changeOrigin: true,
          ws: true
        },
        // ⚠️ 站内信实时推送走 SSE，端点是 /sse/message，**不带 /api 前缀**。
        //    注意：后端该端点的鉴权读的是 Authorization 头，而浏览器原生 EventSource
        //    无法自定义请求头，因此前端实际走「轮询 /api/message/unread-count」这条
        //    后端明确保留的兜底通道（见 MessageSseController 类注释）。此代理为将来
        //    补齐 SSE 客户端预留，当前不影响功能。
        '/sse': {
          target: backendTarget,
          changeOrigin: true,
          ws: false
        },
        // ⚠️ 上传的附件（打卡照片 / 投诉证据 / 资质证件 / 头像）由后端
        //    WebMvcConfig 映射在 /uploads/** 下，不在 /api 之下。
        //    不代理的话，订单详情页的现场照片全是碎图。
        '/uploads': { target: backendTarget, changeOrigin: true },
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
    },

    // Vitest 配置：jsdom 环境跑单测（composables / store / util）
    // （AGENTS.md §6.2 计划 M12 起补 Vitest 单测；本次 desktop-adapt-v2 T-01 提前落地。）
    test: {
      environment: 'jsdom',
      globals: true,
      include: ['src/**/__tests__/**/*.{test,spec}.{js,mjs}'],
      // 排除 e2e 目录（那是 Playwright 的事，不在 Vitest 范围）
      exclude: ['node_modules', 'dist', 'e2e/**'],
      // 单测里需要 matchMedia，用 setup 文件 mock 一份默认实现，避免每个 case 单独打桩
      setupFiles: ['./src/__tests__/setup.js']
    }
  }
})
