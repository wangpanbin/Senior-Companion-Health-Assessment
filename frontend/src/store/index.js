import { createPinia } from 'pinia'

/**
 * Pinia 实例。
 * 模块划分：app（全局 UI / 适老化）、user（登录态与权限）。
 * 各业务模块的状态请放在各自模块目录下，不要塞进 app / user。
 */
const pinia = createPinia()

export default pinia
