/**
 * 共享组件的**枚举型 prop** 取值表与校验器。
 *
 * <h3>为什么需要这个文件</h3>
 *
 * <p>Nl 组件里有一批「取值是有限枚举」的 prop：`tone` / `type` / `scope`。
 * 它们此前全是裸 `{ type: String, default: '...' }` —— 传一个组件没有实现的值
 * （`<NlNoticeBar tone="info">`）会渲染出**一条完全无色的提示条**，且不报任何错。
 * `utils/componentGuard.js` 挡得住「未声明的 prop」，挡不住「已声明但取值非法」。</p>
 *
 * <p>2026-11-26 写产品反馈页时真实踩过这个坑：`NlNoticeBar` 只实现了
 * primary / warning / success / danger 四档，`tone="info"` 静默无色。</p>
 *
 * <h3>为什么列表放在单独模块而不是写在组件里</h3>
 *
 * <p>Vue 的 `defineProps` 是编译器宏，**不能引用 `<script setup>` 里本地声明的变量**
 * （会直接报 `vue/valid-define-props`）。放在这里用 `import` 引入就绕过了那条限制，
 * 同时让 4 个组件的 `tone` 取值表只有**一份**——否则「NlAvatar 支持 purple 而
 * NlNoticeBar 不支持」这种差异会先漂移、再没人知道。</p>
 *
 * @author 银龄伴诊团队
 */

/**
 * 徽标 / 图标 / 头像类组件的底色档（NlAvatar、NlIconBox、NlListRow）。
 *
 * 七个值对应 `variables.scss` 里的七组 `--nl-{tone}-bg` / `--nl-{tone}-text` 变量对。
 */
export const TONES_BADGE = ['primary', 'success', 'warning', 'danger', 'purple', 'info', 'neutral']

/**
 * 提示条色调（NlNoticeBar）。
 *
 * ⚠️ **只有四档**。SCSS 里没有 `is-info` / `is-purple` / `is-neutral` 分支，
 * 传入会渲染出无色条。需要「中性提示」的语义时用 `primary`。
 */
export const TONES_NOTICE = ['primary', 'warning', 'success', 'danger']

/** 状态胶囊的可选强调色（NlStatusChip 的 `tone`，空串表示「按 scope+status 自动映射」） */
export const TONES_CHIP = ['success', 'warning', 'danger', 'info', 'purple', 'neutral']

/** 空态语义（NlEmpty 的 `type`）；非法值会静默退化成 `empty` */
export const EMPTY_TYPES = ['empty', '403', '404', 'network']

/**
 * 校验器工厂。
 *
 * 用法：`validator: oneOf(TONES_BADGE)`。
 *
 * 返回一个 Vue prop validator —— 返回 `false` 时只在开发期打印一次告警，
 * 生产构建里 Vue 会跳过校验，因此没有运行时开销。
 *
 * @param {readonly string[]} allowed 允许取值
 * @param {boolean} [allowEmpty] 是否额外接受空串（用于「空串 = 走默认映射」的 prop）
 * @returns {(v: unknown) => boolean}
 */
export function oneOf(allowed, allowEmpty = false) {
  return (v) => (allowEmpty && v === '') || allowed.includes(v)
}
