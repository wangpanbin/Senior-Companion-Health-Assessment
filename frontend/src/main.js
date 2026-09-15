import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

import 'element-plus/dist/index.css'
import '@/styles/index.scss'
import '@/styles/elderly.scss'

import App from './App.vue'
import router from './router'

/**
 * 应用入口
 *
 * TODO(M11)：Element Plus 目前为全量引入，骨架阶段保证零配置可跑。
 *            适老化改造阶段可改为 unplugin-vue-components 按需引入以减小产物体积。
 */
const app = createApp(App)

// 注册全部 Element Plus 图标为全局组件
for (const [name, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(name, component)
}

app.use(createPinia())
app.use(router)

// locale 固定中文；全局尺寸交给 <el-config-provider> 做适老化动态切换
app.use(ElementPlus, { locale: zhCn })

app.mount('#app')
