/**
 * 百度地图 JSAPI 加载器（ADR-0022）。
 *
 * **这是本项目唯一的前端外网依赖**，也是唯一的「新依赖例外」：
 * 百度 JSAPI 不支持本地打包，必须从 `api.map.baidu.com` 动态加载脚本。
 * 依赖 `@baidumap/jsapi-loader`（官方，MIT）已把三件容易写错的事做成 Promise 语义：
 *
 * 1. **脚本单次注入** —— SPA 里多个组件挂载 / 路由来回切时不会重复 `<script>`；
 * 2. **超时** —— 传 `timeout` 就会 reject，页面据此降级而不是一直转圈；
 * 3. **命名空间版本差异** —— `3.0` / `gl` / `4.0` 分别是 `BMap` / `BMapGL`，loader 统一 resolve。
 *
 * ⚠️ **回退预案**：若评审不接受这个依赖，本文件是唯一需要改的地方 ——
 * 把 `BMapLoader.load(...)` 换成自注入 `<script src="...&callback=...">` 约 40 行，
 * 组件与业务代码零改动。这也正是把它单独抽成一个文件的原因。
 *
 * @see docs/adr/0022-map-live-tracking.md D6
 */
import BMapLoader from '@baidumap/jsapi-loader'

/** AK 由环境变量注入；`.env.development` 只是空占位，真值放 `.env.development.local`（gitignored） */
const AK = import.meta.env.VITE_BAIDU_MAP_AK

/** 加载超时（毫秒）。8 秒既够慢网加载脚本，又不至于让用户盯着空框 */
const LOAD_TIMEOUT_MS = 8000

/**
 * 是否配置了 AK。
 *
 * <p>没配 AK 时**不发请求直接降级** —— 让「本地没配 AK」与「AK 被百度拒了」
 * 在界面上可区分（前者提示未配置，后者提示暂时不可用）。</p>
 */
export function hasMapAk() {
  return typeof AK === 'string' && AK.trim().length > 0
}

/**
 * 懒加载百度地图命名空间。
 *
 * @returns {Promise<object>} resolve 出 `window.BMap`（4.0 下与 `BMapGL` 等同）
 * @throws {Error} AK 缺失时 `code = 'map-no-ak'`；超时 / 脚本失败时 `code = 'map-load-failed'`
 */
export function loadBaiduMap() {
  if (!hasMapAk()) {
    const err = new Error('未配置百度地图 AK')
    err.code = 'map-no-ak'
    return Promise.reject(err)
  }
  return BMapLoader.load({
    ak: AK,
    // 百度官方文档：3.0 与 GL 均为历史版本，新项目用 4.0
    version: '4.0',
    timeout: LOAD_TIMEOUT_MS
  }).catch((cause) => {
    const err = new Error('百度地图加载失败')
    err.code = 'map-load-failed'
    err.cause = cause
    throw err
  })
}

/** 仅供测试：重置 loader 单例状态，避免用例之间互相污染 */
export function __resetBaiduMapLoader() {
  if (typeof BMapLoader.reset === 'function') {
    BMapLoader.reset()
  }
}
