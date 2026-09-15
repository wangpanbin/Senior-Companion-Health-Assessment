/**
 * 接口封装统一出口。
 *
 * 约定：业务代码从 '@/api' 引入，不直接 import request。
 * 每个文件对应 docs/api/ 下的一份文档，改动接口时两处必须同步。
 */

export * as healthApi from './health'
export * as authApi from './auth'
export * as userApi from './user'
export * as orderApi from './order'
export * as executionApi from './execution'
export * as medicationApi from './medication'
export * as reviewApi from './review'
export * as messageApi from './message'
export * as adminApi from './admin'
export * as statisticsApi from './statistics'
