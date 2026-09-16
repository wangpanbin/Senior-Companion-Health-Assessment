import { defineStore } from 'pinia'

/**
 * 下单草稿（M4 · 三步流程的跨页状态）。
 *
 * 为什么用 store 而不是路由 query：
 *   下单要传递的不止「老人 ID」，还有医院名称、地址、经纬度、科室、就诊时间。
 *   全部塞进 query 会得到一个又长又难读的地址栏，而且中文与坐标混在里面
 *   一旦被用户复制分享出去，等于把一单半成品发给别人。
 *
 * ⚠️ 草稿**只存前端表单值，不存任何敏感信息**（无身份证、无手机号明文），
 *    因此放 Pinia 内存即可，不落 localStorage —— 老人/家属共用手机时，
 *    上一个用户的半成品订单不该出现在下一个人的页面上。
 *
 * 生命周期：step1 写入 elderId → step2 写入医院/科室/时间 → step3 提交成功后 `reset()`。
 */
export const useOrderDraftStore = defineStore('orderDraft', {
  state: () => ({
    /** 就诊老人档案 ID */
    elderId: null,
    /** 就诊老人姓名（仅用于确认页展示，来自列表的脱敏值） */
    elderName: '',
    /** 医院：{ name, address, longitude, latitude } */
    hospital: null,
    /** 就诊科室 */
    department: '',
    /** 就诊时间，格式 yyyy-MM-dd HH:mm:ss（后端 OrderCreateDTO.visitTime 的口径） */
    visitTime: '',
    /** 家属备注 */
    remark: ''
  }),

  getters: {
    /** 三步是否都填完了 —— 提交前的最后一道前端闸门 */
    isComplete: (s) => !!(s.elderId && s.hospital?.name && s.department && s.visitTime)
  },

  actions: {
    setElder(elderId, elderName = '') {
      this.elderId = elderId
      this.elderName = elderName
    },

    /**
     * 设置医院与就诊时间。
     * @param {{name:string,address:string,longitude:string,latitude:string}} hospital
     * @param {string} department
     * @param {string} visitTime yyyy-MM-dd HH:mm:ss
     */
    setVisit(hospital, department, visitTime) {
      this.hospital = hospital
      this.department = department
      this.visitTime = visitTime
    },

    setRemark(remark) {
      this.remark = remark
    },

    /** 提交成功或用户放弃流程时调用，避免下一次下单带出上一次的残留值 */
    reset() {
      this.elderId = null
      this.elderName = ''
      this.hospital = null
      this.department = ''
      this.visitTime = ''
      this.remark = ''
    }
  }
})
