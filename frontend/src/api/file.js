import request from '@/utils/request'

/**
 * 通用文件上传接口
 * 对应新增端点：POST /api/file/upload（multipart/form-data）
 *
 * ⚠️ 冻结的接口契约（后端按此实现）：
 *  - bizType 只接受 COMPANION_CERT / COMPLAINT / AVATAR；传别的后端返回参数错误。
 *    （CHECKIN 被刻意拒绝 —— 打卡照片必须走原有 /execution/{id}/photo 端点）
 *  - 服务端按「文件头魔数」判定类型，只接受 jpg / png / webp / pdf；单文件上限 10MB。
 *  - 允许角色：FAMILY / COMPANION / ADMIN；ELDER 会被服务端 403 拦截（message 含「只读」）。
 *  - 返回 { fileId, url, size }，其中 url 形如 /uploads/202609/xxx.jpg（站内相对路径）。
 *
 * 与 uploadExecutionPhoto（@/api/execution）保持完全一致的 multipart 构造方式：
 * 自己 new FormData 并 append 'file' 与 'bizType'，请求头显式声明
 * 'Content-Type': 'multipart/form-data'，不要另起一种写法。
 *
 * 注意：单文件大小 / 类型的前端预校验由调用方负责（design.md §2.6：超限不发起请求）。
 */

/**
 * 上传单个文件
 * @param {File} file 文件本体
 * @param {'COMPANION_CERT'|'COMPLAINT'|'AVATAR'} bizType 业务类型
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
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}
