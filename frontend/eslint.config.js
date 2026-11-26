import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import prettierConfig from 'eslint-config-prettier'

/**
 * ESLint 9 扁平配置。
 * 规范来源：docs/plan/plan.md · 协作规则「前端 ESLint + Prettier，不通过不许提交」。
 */
export default [
  {
    // e2e/ 是 Playwright 测试套件（Node 环境、含 console / process / Buffer 等），
    // 与应用源码的规则不同，故整目录排除在应用 lint 之外。
    ignores: ['dist/**', 'node_modules/**', 'public/**', '*.min.js', 'e2e/**', 'playwright.config.js']
  },

  js.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  prettierConfig,

  {
    // `**/*.mjs` 必须显式列入：扁平配置里 `files` 不匹配的文件**不会继承**
    // `languageOptions.globals`，而 ESLint 默认仍会 lint 它们。
    // frontend/scripts/ 下的报告采集脚本（report-final-shots.mjs）用的是 Node 侧的
    // process / import.meta.dirname —— 只写 `**/*.{js,vue}` 时它们全部被判 no-undef，
    // `pnpm run lint:check` 因此整仓红。补上 .mjs 后这些脚本真正被检查（而不是像
    // e2e/ 那样整目录忽略掉），Node 全局变量也随同一份 globals 生效。
    files: ['**/*.{js,mjs,vue}'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'module',
      globals: {
        window: 'readonly',
        document: 'readonly',
        localStorage: 'readonly',
        sessionStorage: 'readonly',
        navigator: 'readonly',
        location: 'readonly',
        history: 'readonly',
        console: 'readonly',
        setTimeout: 'readonly',
        clearTimeout: 'readonly',
        setInterval: 'readonly',
        clearInterval: 'readonly',
        FormData: 'readonly',
        Blob: 'readonly',
        URL: 'readonly',
        URLSearchParams: 'readonly',
        WebSocket: 'readonly',
        EventSource: 'readonly',
        process: 'readonly'
      }
    },
    rules: {
      // 页面组件名允许单词（Login / Dashboard 等）
      'vue/multi-word-component-names': 'off',
      // 允许使用 v-html 之外的单文件组件名与路由名冲突
      'vue/no-v-html': 'off',
      'no-unused-vars': ['warn', { argsIgnorePattern: '^_' }],
      'no-console': ['warn', { allow: ['warn', 'error'] }]
    }
  },

  {
    // frontend/scripts/ 是 Node 侧的一次性运维脚本（报告截图采集等），
    // 不是应用源码：它的 console.log 就是输出本身（进度与产物清单），
    // 按应用的「只允许 console.warn / error」规则会误伤。
    // 只关这一条，其余规则（含 globals 里的 process）照旧生效。
    files: ['scripts/**/*.{js,mjs}'],
    rules: {
      'no-console': 'off'
    }
  }
]
