/**
 * 测试夹具：在 E2E 测试中搭一个「家属下单 + 陪诊员接单」的最小场景。
 *
 * 用途：
 *   - 业务事件（打卡 / 评价 / 漏服 / 推送）端到端测试需要一条 ACTIVE 订单，
 *     但 seed 里 fam001 名下只有 PENDING/REVIEWED，order 1007 的 family=107 不是 fam001。
 *   - 该 helper 用 API 自建一条 ACCEPTED 订单，返回 orderId 给调用方继续。
 *
 * 清理：测试结束由 fixture 自动回滚（`verify` 阶段按 MAX(id) 水位比对 + 白名单行逐列一致），
 *       调用方无需手动清理。
 */
import { API } from './api'

/**
 * @param {object} args
 * @param {import('@playwright/test').APIRequestContext} args.famCtx   家属侧的已鉴权 ctx
 * @param {import('@playwright/test').APIRequestContext} args.compCtx  陪诊员侧的已鉴权 ctx
 * @param {object} [args.opts] 订单字段覆写；默认与 seed 订单 1007 同模板（海南海口）
 * @returns {Promise<{orderId: number, hospital: string, department: string, address: string,
 *                   longitude: string, latitude: string}>}
 */
export async function seedAcceptedOrder({
  famCtx,
  compCtx,
  opts = {}
}) {
  const {
    elderId = 401, // 张德海，绑定 fam001（FRONTEND_CONTRACT §10.8）
    hospital = '海南省人民医院',
    department = '心血管内科',
    // 未来时，避免被自动取消逻辑触达
    visitTime = '2099-09-20 09:30:00',
    address = '海南省海口市琼山区',
    longitude = '110.311422',
    latitude = '20.021674',
    remark = 'e2e seed'
  } = opts

  // ⚠️ OrderCreateResultVO 字段是 orderId（不是 id）
  const create = await (
    await famCtx.post(`${API}/order`, {
      data: { elderId, hospital, department, visitTime, address, longitude, latitude, remark }
    })
  ).json()
  if (create.code !== 200) {
    throw new Error(`seed 下单失败：${JSON.stringify(create)}`)
  }
  const orderId = create.data.orderId

  // ⚠️ accept 不接受请求体（不要传 data）
  const accept = await (await compCtx.post(`${API}/order/${orderId}/accept`)).json()
  if (accept.code !== 200) {
    throw new Error(`seed 接单失败：${JSON.stringify(accept)}`)
  }

  return { orderId, hospital, department, address, longitude, latitude }
}

/** plan §M5 / §M8 的推送时延上界（毫秒） */
export const PUSH_DEADLINE_MS = 3000

/** 等待事务同步落库的常用延迟（毫秒）—— 比上界小一半，留余量给断言 */
export const PUSH_SETTLE_MS = 1500