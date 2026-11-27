/**
 * 坐标系转换（ADR-0022）。
 *
 * 项目里有三套坐标口径，混用一次就是几百米的偏移：
 *
 * | 来源 | 坐标系 |
 * |---|---|
 * | 浏览器 `navigator.geolocation` | **WGS84**（GPS 原生值） |
 * | 后端 `companion_track` / `order_checkin` / `companion_order` | **WGS84**（原样存，零转换） |
 * | 百度地图 | **BD09** |
 *
 * **规则：库里与接口里一律 WGS84，只在渲染前转 BD09。**
 *
 * 反向做（落库改存 BD09）会让打卡 `4001` 的 2000 米阈值凭空多出约 500 米偏差，
 * 而存量数据没有坐标系标记、无法区分新旧口径。渲染层偏移错了肉眼能看见，
 * 判定层偏移错了只有纠纷时才被翻出来。
 *
 * @see docs/adr/0022-map-live-tracking.md D5
 */

/** WGS84 → GCJ02（国测局）用到的椭球参数（GCJ-02 是「火星坐标」，无官方公式，此为实现事实标准） */
const PI = Math.PI
const A = 6378245.0 // 长半轴（Krasovsky 1940）
const EE = 0.00669342162296594 // 偏心率平方（Krasovsky 1940；取 15 位有效数字，再多位就丢精度）
const X_PI = (PI * 3000.0) / 180.0 // BD09 的额外偏移常数

/**
 * 是否在中国境外。
 *
 * <p>GCJ02 的偏移只在中国境内生效 —— 境外坐标做偏移反而会把它推错。
 * 判据用「经纬度矩形」，精度足够（本项目服务范围是海口市）。</p>
 */
function outOfChina(lng, lat) {
  return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271
}

function transformLat(x, y) {
  let ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
  ret += ((20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0) / 3.0
  ret += ((20.0 * Math.sin(y * PI) + 40.0 * Math.sin((y / 3.0) * PI)) * 2.0) / 3.0
  ret += ((160.0 * Math.sin((y / 12.0) * PI) + 320 * Math.sin((y * PI) / 30.0)) * 2.0) / 3.0
  return ret
}

function transformLng(x, y) {
  let ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
  ret += ((20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0) / 3.0
  ret += ((20.0 * Math.sin(x * PI) + 40.0 * Math.sin((x / 3.0) * PI)) * 2.0) / 3.0
  ret += ((150.0 * Math.sin((x / 12.0) * PI) + 300.0 * Math.sin((x / 30.0) * PI)) * 2.0) / 3.0
  return ret
}

/**
 * WGS84 → GCJ02。
 *
 * @param {number} lng 经度（WGS84）
 * @param {number} lat 纬度（WGS84）
 * @returns {{lng: number, lat: number}} GCJ02 坐标；境外原样返回
 */
export function wgs84ToGcj02(lng, lat) {
  if (outOfChina(lng, lat)) {
    return { lng, lat }
  }
  let dLat = transformLat(lng - 105.0, lat - 35.0)
  let dLng = transformLng(lng - 105.0, lat - 35.0)
  const radLat = (lat / 180.0) * PI
  let magic = Math.sin(radLat)
  magic = 1 - EE * magic * magic
  const sqrtMagic = Math.sqrt(magic)
  dLat = (dLat * 180.0) / (((A * (1 - EE)) / (magic * sqrtMagic)) * PI)
  dLng = (dLng * 180.0) / ((A / sqrtMagic) * Math.cos(radLat) * PI)
  return { lng: lng + dLng, lat: lat + dLat }
}

/**
 * GCJ02 → BD09。
 *
 * @param {number} lng 经度（GCJ02）
 * @param {number} lat 纬度（GCJ02）
 * @returns {{lng: number, lat: number}} BD09 坐标
 */
export function gcj02ToBd09(lng, lat) {
  const z = Math.sqrt(lng * lng + lat * lat) + 0.00002 * Math.sin(lat * X_PI)
  const theta = Math.atan2(lat, lng) + 0.000003 * Math.cos(lng * X_PI)
  return {
    lng: z * Math.cos(theta) + 0.0065,
    lat: z * Math.sin(theta) + 0.006
  }
}

/**
 * WGS84 → BD09（两步串联）。**地图渲染只用这一个函数。**
 *
 * @param {number|string} lng 经度（WGS84）；字符串会被转成数字
 * @param {number|string} lat 纬度（WGS84）
 * @returns {{lng: number, lat: number}} BD09 坐标；入参非法时返回 `null`
 */
export function wgs84ToBd09(lng, lat) {
  // ⚠️ 必须显式排 null / undefined / 空串：Number(null) === 0、Number('') === 0，
  //    只靠 Number.isFinite 会把「没传」当成「经度 0」（几内亚湾），
  //    地图会把镜头飞到非洲，且没有任何报错
  if (lng == null || lat == null || lng === '' || lat === '') {
    return null
  }
  const x = Number(lng)
  const y = Number(lat)
  if (!Number.isFinite(x) || !Number.isFinite(y)) {
    return null
  }
  const gcj = wgs84ToGcj02(x, y)
  return gcj02ToBd09(gcj.lng, gcj.lat)
}
