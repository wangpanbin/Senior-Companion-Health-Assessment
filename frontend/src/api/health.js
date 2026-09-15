import request from '@/utils/request'

/**
 * 通用与健康检查接口
 * 对应文档：docs/api/README.md · 通用接口
 */

/** 服务健康检查（无需登录） */
export function getHealth() {
  return request({ url: '/health', method: 'get' })
}
