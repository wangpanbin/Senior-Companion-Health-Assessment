import { ref } from 'vue'
import { ElMessage } from 'element-plus'

/**
 * 「打开裁定对话框」的共性逻辑（E4）。
 *
 * <h3>收敛了什么</h3>
 *
 * <p>评价有效性裁定有两个入口，两处原先各自抄了一份
 * {@code rulingVisible / rulingTarget / openRuling / handleRulingSuccess}：
 *
 * <ul>
 *   <li>{@code views/admin/complaint.vue} —— 评价申诉（REVIEW_APPEAL）行内发起，
 *       评价对象要先按 {@code complaint.orderId} 反查；</li>
 *   <li>{@code views/admin/review-audit.vue} —— 评价巡查页直接用行数据。</li>
 * </ul>
 *
 * <p>两处的差别**只有「评价对象从哪来」**，其余（已裁定过的挡住、打开 / 关闭、
 * 成功后回退一页）逐字相同。抄两份的后果是「一个入口挡住了已裁定的评价、
 * 另一个忘了挡」这类不一致 —— 而对话框组件本身管不到按钮这一层。</p>
 *
 * @param {object} opts
 * @param {() => void|Promise<void>} opts.reload 裁定成功后刷新列表（含回退一页）
 * @returns {{rulingVisible: import('vue').Ref<boolean>,
 *            rulingTarget: import('vue').Ref<object|null>,
 *            rulingLoading: import('vue').Ref<boolean>,
 *            openRulingWith: (resolver: () => object|Promise<object>) => Promise<void>,
 *            handleRulingSuccess: () => Promise<void>}}
 */
export function useReviewRuling({ reload }) {
  /** 裁定对话框是否打开 */
  const rulingVisible = ref(false)
  /** 待裁定的评价 */
  const rulingTarget = ref(null)
  /** 反查评价的 in-flight 标记（驳回/巡查两处行内按钮的 loading） */
  const rulingLoading = ref(false)

  /**
   * 打开裁定对话框。
   *
   * @param {() => object|Promise<object>} resolve 取评价对象的函数。
   *   返回 {@code null} 表示「该订单还没有评价」—— 此时照样打开对话框，
   *   让组件自己显示空态（比一句 toast 更能说清「为什么裁不了」）。
   */
  async function openRulingWith(resolve) {
    if (rulingLoading.value) return
    rulingLoading.value = true
    try {
      const review = await resolve()
      // 已裁定过的评价不再给裁（后端也会返 409，这里提前挡住）。
      // 巡查页能直接判断（行数据里就有 isValid）；申诉入口反查回来的
      // review 同样带 isValid，两个入口共用这一道判断。
      if (review && review.isValid === false) {
        ElMessage.info('该评价已被裁定无效')
        return
      }
      rulingTarget.value = review || null
      rulingVisible.value = true
    } catch {
      // 后端报错由 request.js 拦截器统一提示，这里不重复弹
    } finally {
      rulingLoading.value = false
    }
  }

  /** 裁定成功后刷新列表（该行的有效性标记应随之翻转） */
  async function handleRulingSuccess() {
    await reload()
  }

  return { rulingVisible, rulingTarget, rulingLoading, openRulingWith, handleRulingSuccess }
}
