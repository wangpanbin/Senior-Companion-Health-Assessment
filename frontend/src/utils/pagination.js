/**
 * 分页回退的判据（纯函数，不依赖 Vue）。
 *
 * <h3>为什么单独抽一个文件</h3>
 *
 * <p>「写操作之后要不要把页码退回去」这个判断，后台 7 个列表页与家属端 1 个列表页
 * 都要用。早先每页各写一份 {@code if (某条不在本页) page--}，于是同一件事有 8 种写法，
 * 且其中一种是错的（见下）。判据收敛到这里，各页只负责调用。</p>
 *
 * <p>⚠️ 判据必须是「<b>本页是否被掏空</b>」，**不是**「刚处理的那条还在不在本页」：
 * 后者在本页还有别的行时也会跳页，把用户从正在看的内容上拽走 ——
 * 那是用一个毛病换另一个毛病。</p>
 *
 * @author 银龄伴诊团队
 */

/**
 * 当前页是否需要回退一页。
 *
 * @param {number} page 当前页码（1 起）
 * @param {Array} records 当前页的记录数组
 * @returns {boolean} true = 本页已被掏空且不在第 1 页 → 应回退
 */
export function shouldRollbackPage(page, records) {
  return page > 1 && Array.isArray(records) && records.length === 0
}
