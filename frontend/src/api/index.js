/**
 * 接口封装统一出口。
 *
 * 约定：业务代码从 '@/api' 引入，不直接 import request。
 * 每个文件对应 docs/api/ 下的一份文档，改动接口时两处必须同步。
 */

export * as healthApi from './health'
export * as authApi from './auth'
export * as userApi from './user'
export * as elderHealthApi from './elderHealth'
// 体征记录（V4 · P2-2）：此前漏了这一个，4 个页面只能按路径 `@/api/vitalSign` 直接 import，
// 与「业务代码从 @/api 引入」的约定不一致（最终复盘 FE-10）。
export * as vitalSignApi from './vitalSign'
export * as orderApi from './order'
export * as executionApi from './execution'
export * as medicationApi from './medication'
export * as reviewApi from './review'
export * as feedbackApi from './feedback'
export * as messageApi from './message'
export * as adminApi from './admin'
export * as statisticsApi from './statistics'
export * as fileApi from './file'
export * as chatApi from './chat'
export * as assistantApi from './assistant'
export * as examPrepApi from './examPrep'
