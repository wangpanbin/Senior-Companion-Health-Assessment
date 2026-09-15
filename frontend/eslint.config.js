import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import prettierConfig from 'eslint-config-prettier'

/**
 * ESLint 9 扁平配置。
 * 规范来源：plan.md · 协作规则「前端 ESLint + Prettier，不通过不许提交」。
 */
export default [
  {
    ignores: ['dist/**', 'node_modules/**', 'public/**', '*.min.js']
  },

  js.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  prettierConfig,

  {
    files: ['**/*.{js,vue}'],
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
  }
]
