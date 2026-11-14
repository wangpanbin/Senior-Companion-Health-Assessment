import request from '@/utils/request'

/**
 * 检查准备须知接口
 * 对应文档：docs/api/14-exam-prep.md
 */

/**
 * 检查准备须知列表（静态字典 EXAM_PREP，已登录即可查，无参数）
 * 每条 remark 自带来源 URL 与免责声明 —— 前端原样展示，不得截掉免责段
 */
export function listExamPrep() {
  return request({ url: '/exam-prep', method: 'get' })
}
