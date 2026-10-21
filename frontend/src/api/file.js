import request from '@/utils/request'

/**
 * 通用文件上传接口
 * 对应文档：docs/api/10-file-upload.md
 * 负责模块：M5 打卡照片 / M9 资质证件 · 投诉证据 · 头像
 * 对应端点：POST /api/file/upload（multipart/form-data）、GET /api/file/cert（受控证件下载）
 *
 * ⚠️ 冻结的接口契约（后端按此实现）：
 *  - bizType 接受 COMPANION_CERT / COMPLAINT / AVATAR / CHAT。
 *    （CHECKIN 被刻意拒绝 —— 打卡照片必须走原有 /execution/{id}/photo 端点）
 *  - 服务端按文件头判定类型；非 CHAT 仍为 jpg/png/webp/pdf、10MB。
 *  - CHAT 支持 jpg/png/webp/mp4、50MB；对应 docs/api/11-order-chat.md（M13）。
 *  - 允许角色：FAMILY / COMPANION / ADMIN；ELDER 会被服务端 403 拦截（message 含「只读」）。
 *  - 返回 { fileId, url, size }，其中 url 形如 /uploads/202609/xxx.jpg（站内相对路径）。
 *
 * 与 uploadExecutionPhoto（@/api/execution）保持完全一致的 multipart 构造方式：
 * 自己 new FormData 并 append 'file' 与 'bizType'，请求头显式声明
 * 'Content-Type': 'multipart/form-data'，不要另起一种写法。
 *
 * 注意：单文件大小 / 类型的前端预校验由调用方负责（docs/design/design.md §2.6：超限不发起请求）。
 */

/**
 * 上传单个文件
 * @param {File} file 文件本体
 * @param {'COMPANION_CERT'|'COMPLAINT'|'AVATAR'|'CHAT'} bizType 业务类型
 * @returns {Promise<{fileId:string|number, url:string, size:number}>}
 */
export function uploadFile(file, bizType) {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('bizType', bizType)
  return request({
    url: '/file/upload',
    method: 'post',
    data: formData,
    ...(bizType === 'CHAT' ? { timeout: 180000 } : {}),
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

/**
 * 受控读取资质证件：GET /api/file/cert?url=...
 *
 * 资质证件（bizType=COMPANION_CERT）的 /uploads 原始 URL 已被服务端守卫拦截，
 * 未认证者直连一律 403；证件预览必须经本端点取二进制，再转成 blob URL 交给
 * <img> / 下载。其余类型（头像 / 打卡照片 / 聊天媒体）仍可 <img> 直连，无需本端点。
 *
 * 仅上传人本人或管理员可访问，无权限时拦截器会统一弹错并 reject。
 *
 * @param {string} url 证件原始 URL（形如 /uploads/202609/xxx.jpg）
 * @returns {Promise<string>} blob URL（调用方负责在适当时机 URL.revokeObjectURL）
 */
export function fetchCertObjectUrl(url) {
  return request({
    url: '/file/cert',
    method: 'get',
    params: { url },
    responseType: 'blob'
  }).then((blob) => URL.createObjectURL(blob))
}
