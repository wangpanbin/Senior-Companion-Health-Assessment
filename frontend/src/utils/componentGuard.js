/**
 * 共享组件的 prop / 插槽自检（**仅开发期生效**）。
 *
 * <h3>为什么值得单独抽一个模块</h3>
 *
 * <p>Vue 对「未声明的 prop」和「未声明的插槽」都是<b>静默处理</b>：</p>
 * <ul>
 *   <li>未声明的 prop → 落到根元素上，组件内部完全感知不到；</li>
 *   <li>未声明的插槽 → 内容被<b>直接丢弃</b>，页面看上去「只是少了个按钮」。</li>
 * </ul>
 *
 * <p>这两种失效在<b>共享组件</b>上尤其贵：一次写错，全站所有用到它的页面都跟着错，
 * 而且页面上看不出任何异常。仓库里真实踩过的两个例子：</p>
 * <ul>
 *   <li>{@code <NlEmpty text="暂无评价" />} —— 没有 {@code text} 这个 prop，
 *       于是它静默退化到默认文案「暂无数据」，页面看起来「就是文案不对」；</li>
 *   <li>{@code <NlEmpty><template #action>} —— 当时组件还没有 {@code action} 插槽，
 *       整段按钮被丢弃，403 页<b>一个按钮都没有</b>，用户被拦在死路上。</li>
 * </ul>
 *
 * <p>所以这里在开发期把「写错」这件事喊出来。生产构建下不告警（不进包、不影响线上）。</p>
 *
 * @author 银龄伴诊团队
 */

/**
 * 检查组件收到的属性与插槽，命中未声明项时告警。
 *
 * @param {string} componentName 组件名（用于告警定位，如 'NlEmpty'）
 * @param {object} api
 * @param {string[]} api.props   该组件声明的属性名
 * @param {string[]} [api.slots] 该组件声明的插槽名（不含 '#'）
 * @param {object} attrs        `useAttrs()` 的结果
 * @param {string[]} [slotKeys]  `Object.keys(useSlots())` 的结果
 */
export function guardComponentApi(componentName, api, attrs, slotKeys = []) {
  if (!import.meta.env.DEV) return

  const knownProps = new Set(api.props || [])
  for (const key of Object.keys(attrs || {})) {
    // 事件监听（onXxx）、指令（v-xxx）、原生透传属性不算误用
    if (key.startsWith('on') || key.startsWith('v-')) continue
    if (key === 'class' || key === 'style' || key === 'key' || key === 'ref') continue
    if (!knownProps.has(key)) {
      console.warn(
        `[${componentName}] 未知属性 "${key}"：本组件只接受 ${knownProps.size ? [...knownProps].join(' / ') : '（无）'}。` +
          '（例如 title 写成 text 会静默失效，页面只显示默认文案）'
      )
    }
  }

  const knownSlots = new Set(api.slots || [])
  for (const key of slotKeys) {
    if (!knownSlots.has(key)) {
      console.warn(
        `[${componentName}] 未知插槽 "#${key}"：本组件只接受 ${knownSlots.size ? [...knownSlots].map((s) => '#' + s).join(' / ') : '（无）'} 插槽。` +
          '（插槽名写错会被 Vue 静默丢弃，页面上的内容会凭空消失）'
      )
    }
  }
}
