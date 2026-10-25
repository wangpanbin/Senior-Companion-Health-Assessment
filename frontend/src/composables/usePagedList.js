import { computed } from 'vue'
import { shouldRollbackPage } from '@/utils/pagination'

/**
 * 后台分页列表的共性逻辑（P1-8）。
 *
 * <h3>收敛了什么</h3>
 *
 * <p>此前 7 个后台页（用户管理 / 投诉管理 / 订单纠纷 / 陪诊员审核 / 履约风险 /
 * 操作日志 / 订单管理）各自逐字复制了同一套三件套：{@code pageDrained}、
 * {@code onPrevPage}、{@code reloadAfterMutate}。核心判据早就收敛在
 * {@code utils/pagination.js}，但每个页面还各留了一份薄包装与一份注释 ——
 * 改判据要动 7 个地方，漏一个就出现「有的页回退、有的页不回退」的不一致。</p>
 *
 * <h3>三件套各自的语义</h3>
 *
 * <ol>
 *   <li><b>pageDrained</b> —— 空态分两种：① 本页已空、上一页还有（写操作把记录
 *       处理掉了，页码停在旧页上）；② 真的没有记录（筛选没命中）。
 *       两种都显示「暂无投诉」的话，管理员分不清是数据没了还是翻过头了。</li>
 *   <li><b>onPrevPage</b> —— 空态里的「返回上一页」。</li>
 *   <li><b>reloadAfterMutate</b> —— 写操作成功后重载列表，必要时回退一页。</li>
 * </ol>
 *
 * <p>⚠️ 回退判据是「<b>本页是否被掏空</b>」，**不是**「刚处理的那条还在不在本页」：
 * 后者在本页还有别的行时也会跳页，把管理员从正在看的内容上拽走。</p>
 *
 * @author 银龄伴诊团队
 */

/**
 * @param {object} opts
 * @param {import('vue').Ref<number>} opts.page 页码 ref
 * @param {import('vue').Ref<boolean>} opts.isEmpty 「当前页没有记录且不在加载中」
 * @param {import('vue').Ref<Array>} opts.records 当前页的记录数组
 * @param {() => void|Promise<void>} opts.reload 重新拉取当前页
 */
export function usePagedList({ page, isEmpty, records, reload }) {
  /** 本页已被掏空且不在第 1 页 → 该往回翻 */
  const pageDrained = computed(() => isEmpty.value && page.value > 1)

  /** 空态里的「返回上一页」 */
  function onPrevPage() {
    if (page.value > 1) page.value -= 1
    reload()
  }

  /**
   * 写操作成功后重新加载列表，并在必要时回退一页。
   *
   * <p>典型场景：筛选「待处理」停在第 2 页且该页只剩 1 条，处理掉后这条离开筛选集，
   * 同页返回空、总数却还在 —— 管理员看到的是「暂无投诉」，得自己手动翻回第 1 页。</p>
   */
  async function reloadAfterMutate() {
    await reload()
    if (!shouldRollbackPage(page.value, records.value)) return
    page.value -= 1
    await reload()
  }

  return { pageDrained, onPrevPage, reloadAfterMutate }
}
