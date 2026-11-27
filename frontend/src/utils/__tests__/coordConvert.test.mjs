import { describe, expect, it } from 'vitest'
import { gcj02ToBd09, wgs84ToBd09, wgs84ToGcj02 } from '@/utils/coordConvert'

/**
 * 坐标系转换单测（ADR-0022 D5）。
 *
 * 这组断言的作用不是「证明公式对」（公式是公开的实现事实标准），
 * 而是**钉住偏移量级**：WGS84 → BD09 在海南/北京一带约 500~600 米。
 *
 * 为什么值得单独测：库里存的、接口返回的、打卡 4001 判定用的都是 WGS84，
 * 只有地图渲染用 BD09。若哪天有人「顺手」把后端也改成存 BD09，
 * 4001 的 2000 米阈值会凭空多出 25% 的偏差，而地图看起来一切正常 ——
 * 这里的大致量级断言至少能让「转换被挪走或改反」这件事在 CI 里红一次。
 */

/** 两点球面距离（米），与被测代码无关，用最朴素的 Haversine 独立算 */
function distanceMeters(lng1, lat1, lng2, lat2) {
  const R = 6371008.8
  const rad = (d) => (d * Math.PI) / 180
  const dLat = rad(lat2 - lat1)
  const dLng = rad(lng2 - lng1)
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(rad(lat1)) * Math.cos(rad(lat2)) * Math.sin(dLng / 2) ** 2
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(a)))
}

describe('coordConvert · WGS84 → GCJ02', () => {
  it('海口市政府附近的偏移约 400~700 米', () => {
    const wgs = { lng: 110.3312, lat: 20.0315 }
    const gcj = wgs84ToGcj02(wgs.lng, wgs.lat)
    const offset = distanceMeters(wgs.lng, wgs.lat, gcj.lng, gcj.lat)

    expect(offset).toBeGreaterThan(400)
    expect(offset).toBeLessThan(700)
  })

  it('天安门附近的偏移同样在几百米量级（不是只在海南成立）', () => {
    const gcj = wgs84ToGcj02(116.3912, 39.9073)
    const offset = distanceMeters(116.3912, 39.9073, gcj.lng, gcj.lat)

    expect(offset).toBeGreaterThan(400)
    expect(offset).toBeLessThan(700)
  })

  it('境外坐标原样返回（偏移只在中国境内生效）', () => {
    const gcj = wgs84ToGcj02(-74.006, 40.7128) // 纽约
    expect(gcj.lng).toBe(-74.006)
    expect(gcj.lat).toBe(40.7128)
  })

  it('经度方向恒为正（中国境内 GCJ02 的稳定性质）', () => {
    // 纬度方向**不是**恒为正：海口一带 GCJ02 纬度比 WGS84 小，北方城市比它大，
    // 所以这里只能钉经度方向（实现事实标准里这条是稳定的）
    expect(wgs84ToGcj02(110.3312, 20.0315).lng).toBeGreaterThan(110.3312)
    expect(wgs84ToGcj02(116.3912, 39.9073).lng).toBeGreaterThan(116.3912)
  })
})

describe('coordConvert · GCJ02 → BD09', () => {
  it('偏移约 0.8~1.1 公里（BD09 在 GCJ02 之上再叠 +0.0065 经度 / +0.006 纬度）', () => {
    const gcj = { lng: 110.3361, lat: 20.0358 }
    const bd = gcj02ToBd09(gcj.lng, gcj.lat)
    const offset = distanceMeters(gcj.lng, gcj.lat, bd.lng, bd.lat)

    expect(offset).toBeGreaterThan(800)
    expect(offset).toBeLessThan(1100)
  })

  it('已知点：天安门 GCJ02 → BD09 落在 116.4038/39.9156 附近', () => {
    const bd = gcj02ToBd09(116.397428, 39.90923)
    expect(bd.lng).toBeCloseTo(116.4038, 3)
    expect(bd.lat).toBeCloseTo(39.9156, 3)
  })
})

describe('coordConvert · WGS84 → BD09（渲染唯一入口）', () => {
  it('两级串联后的总偏移约 1.0~1.5 公里', () => {
    const wgs = { lng: 110.3312, lat: 20.0315 }
    const bd = wgs84ToBd09(wgs.lng, wgs.lat)
    const offset = distanceMeters(wgs.lng, wgs.lat, bd.lng, bd.lat)

    expect(offset).toBeGreaterThan(1000)
    expect(offset).toBeLessThan(1500)
  })

  it('接受字符串入参（接口返回的坐标是 6 位小数字符串）', () => {
    const fromString = wgs84ToBd09('110.331200', '20.031500')
    const fromNumber = wgs84ToBd09(110.3312, 20.0315)

    expect(fromString.lng).toBeCloseTo(fromNumber.lng, 6)
    expect(fromString.lat).toBeCloseTo(fromNumber.lat, 6)
  })

  it('非法入参返回 null，而不是 NaN 坐标（NaN 会让百度地图整张图崩掉）', () => {
    expect(wgs84ToBd09(null, 20.03)).toBeNull()
    expect(wgs84ToBd09('abc', 'def')).toBeNull()
    expect(wgs84ToBd09(undefined, undefined)).toBeNull()
  })

  it('幂等性：同一输入两次调用结果完全一致（纯函数，无内部状态）', () => {
    const a = wgs84ToBd09('110.3112', '20.0215')
    const b = wgs84ToBd09('110.3112', '20.0215')
    expect(a).toEqual(b)
  })
})
