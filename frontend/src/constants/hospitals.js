/**
 * 医院与科室常量。
 *
 * ⚠️ **真源是数据库 `sys_dict`**（`dict_type = 'HOSPITAL' | 'DEPARTMENT'`，见 `backend/sql/V2__seed_data.sql`）。
 *    后端一期**没有**暴露字典查询接口（无 `SysDictController`），而 `OrderCreateDTO`
 *    收的是自由文本（`hospital` / `department` / `address`），所以这份清单只能落在前端。
 *    **改动 `sys_dict` 时必须同步改这里**，否则界面上的选项会与统计口径对不上。
 *
 * ⚠️ **坐标是必填项，不是装饰**（这是联调时最容易踩的坑）：
 *    `OrderCreateDTO` 的 `longitude` / `latitude` 若不传，订单坐标会被写成 NULL，
 *    而 M5 打卡的距离校验是 `GeoUtil.distanceMeters(打卡点, 订单坐标)` ——
 *    订单坐标为 NULL 时该计算返回 null，**校验被静默跳过**。
 *    结果是「打卡坐标超出阈值 → 4001」这条验收标准在任何真实订单上都永远不可能触发，
 *    看似实现了、实际是死代码。所以这里的每个医院都带坐标，下单时一并提交。
 *
 *    坐标为海口市区的**近似演示值**（精度到约百米级），仅用于距离校验，
 *    不作为导航用途（一期已明确砍掉地图导航）。
 */

/** 医院列表，`code` 与 sys_dict.dict_code 对齐 */
export const HOSPITALS = [
  {
    code: 'H01',
    name: '海南省人民医院',
    level: '三甲',
    address: '海口市秀英区秀华路19号',
    longitude: '110.294000',
    latitude: '20.018000'
  },
  {
    code: 'H02',
    name: '海南医学院第一附属医院',
    level: '三甲',
    address: '海口市龙华区龙华路31号',
    longitude: '110.331000',
    latitude: '20.032000'
  },
  {
    code: 'H03',
    name: '海南医学院第二附属医院',
    level: '三甲',
    address: '海口市龙华区椰海大道368号',
    longitude: '110.319000',
    latitude: '19.995000'
  },
  {
    code: 'H04',
    name: '海口市人民医院',
    level: '三甲',
    address: '海口市美兰区人民大道43号',
    longitude: '110.356000',
    latitude: '20.046000'
  },
  {
    code: 'H05',
    name: '海南省中医院',
    level: '三甲',
    address: '海口市美兰区和平北路47号',
    longitude: '110.354000',
    latitude: '20.039000'
  },
  {
    code: 'H06',
    name: '海口市中医医院',
    level: '三甲',
    address: '海口市琼山区文庄路59号',
    longitude: '110.364000',
    latitude: '19.993000'
  },
  {
    code: 'H07',
    name: '海南省妇幼保健院',
    level: '三甲',
    address: '海口市龙华区龙昆南路15号',
    longitude: '110.331000',
    latitude: '20.009000'
  },
  {
    code: 'H08',
    name: '海南省肿瘤医院',
    level: '三甲',
    address: '海口市秀英区长滨西四街6号',
    longitude: '110.283000',
    latitude: '20.006000'
  },
  {
    code: 'H09',
    name: '海口市第三人民医院',
    level: '二甲',
    address: '海口市琼山区建国路79号',
    longitude: '110.358000',
    latitude: '19.990000'
  },
  {
    code: 'H10',
    name: '联勤保障部队第九二八医院',
    level: '三甲',
    address: '海口市龙华区龙昆南路100号',
    longitude: '110.322000',
    latitude: '20.025000'
  }
]

/** 科室列表，`code` 与 sys_dict.dict_code 对齐 */
export const DEPARTMENTS = [
  '心血管内科',
  '神经内科',
  '内分泌科',
  '呼吸内科',
  '消化内科',
  '骨科',
  '眼科',
  '耳鼻喉科',
  '皮肤科',
  '泌尿外科',
  '康复医学科',
  '中医科',
  '老年病科',
  '肿瘤内科',
  '肾内科'
]

/**
 * 后端 `OrderServiceImpl#resolveFee` 的前端镜像，**仅用于下单前的价格预览**。
 *
 * ⚠️ 真源在后端（`OrderServiceImpl` 第 110-115 行）：
 *      BASE_FEE = 128.00，OFF_HOURS_EXTRA = 30.00 → 夜间/周末 158.00
 *      NIGHT_START_HOUR = 18，NIGHT_END_HOUR = 8
 *    即 `weekend || hour >= 18 || hour < 8` 时加价。
 *
 * ⚠️ 这份只做展示，**下单时不传 `fee`**，让后端算 —— 否则会出现
 *    「页面显示 128、入库 158」这类前端算错而无人察觉的问题。
 *    后端 `resolveFee(given, visitTime)` 在 `given != null` 时直接用传入值，
 *    不会替你纠正。
 */
export const FEE_BASE = 128
export const FEE_OFF_HOURS_EXTRA = 30
export const NIGHT_START_HOUR = 18
export const NIGHT_END_HOUR = 8

/**
 * 预估服务费（展示用）。
 * @param {Date} dateTime 就诊时间
 */
export function estimateFee(dateTime) {
  const day = dateTime.getDay()
  const hour = dateTime.getHours()
  const isWeekend = day === 0 || day === 6
  const isNight = hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR
  return isWeekend || isNight ? FEE_BASE + FEE_OFF_HOURS_EXTRA : FEE_BASE
}
