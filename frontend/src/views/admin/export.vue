<script setup>
/**
 * W-07 数据导出
 *
 * 导出接口直接返回 Blob（前端拦截器对非统一结构透传，所以拿到的是 Blob 本体）。
 * ⚠️ 关键坑：后端有导出条数上限，超限时仍是 HTTP 200 但 Content-Type 变成 json
 * （body 是一段错误信息），必须按 blob.type 甄别，否则用户会下载到一个内容是报错信息的 .xlsx。
 *
 * 后端只实现了订单与用户两类导出（见 statistics 导出接口），因此范围只保留 ORDER / USER；
 * 筛选条件与列表页一致（status / keyword / role / 时间区间），直接复用同一套查询参数。
 */
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { NlCard, NlNoticeBar } from '@/components'
import { exportOrders, exportUsers } from '@/api/statistics'
import { today } from '@/utils/format'

const form = ref({
  // 时间区间用 value-format 直接拿 "yyyy-MM-dd" 字符串，贴合后端 startDate/endDate
  range: null,
  scope: 'ORDER',
  role: '',
  status: '',
  keyword: ''
})

const exporting = ref(false)

/** 把表单拍平成两种导出各自认的查询参数（空值不传，避免后端按空串精确匹配） */
function buildParams() {
  const p = {}
  if (form.value.range && form.value.range.length === 2) {
    p.startDate = form.value.range[0]
    p.endDate = form.value.range[1]
  }
  if (form.value.scope === 'USER') {
    if (form.value.role) p.role = form.value.role
  } else {
    if (form.value.status) p.status = form.value.status
    if (form.value.keyword && form.value.keyword.trim()) p.keyword = form.value.keyword.trim()
  }
  return p
}

/** 用 Blob 触发浏览器下载 */
function triggerDownload(blob, filename) {
  const url = window.URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  window.URL.revokeObjectURL(url)
}

async function exportFile() {
  if (exporting.value) return
  try {
    await ElMessageBox.confirm(
      '导出数据将按当前筛选条件实时查询；敏感字段由后端脱敏，不会泄露明文。',
      '确认导出',
      { confirmButtonText: '导出 Excel' }
    )
  } catch {
    // 用户取消，直接返回
    return
  }

  exporting.value = true
  try {
    // 拦截器对 blob 响应透传，这里拿到的就是 Blob 本体
    const blob = form.value.scope === 'USER'
      ? await exportUsers(buildParams())
      : await exportOrders(buildParams())

    // 甄别「超限报错」：超限时后端仍 200，但 Content-Type 是 json，body 是错误信息
    if (blob && blob.type && blob.type.includes('json')) {
      const text = await blob.text()
      let msg = '导出失败，请缩小筛选范围后重试'
      try {
        const err = JSON.parse(text)
        if (err && err.message) msg = err.message
      } catch {
        // 非标准 JSON，沿用默认提示
      }
      ElMessage.error(msg)
      return
    }

    const prefix = form.value.scope === 'USER' ? '用户导出' : '订单导出'
    triggerDownload(blob, `${prefix}_${today()}.xlsx`)
    ElMessage.success('已开始下载')
  } catch {
    // 请求层异常拦截器已弹提示，这里只兜底
  } finally {
    exporting.value = false
  }
}
</script>

<template>
  <NlCard title="数据导出" plain>
    <NlNoticeBar tone="warning">
      密码 / 完整手机号 / 身份证号明文<b>不允许导出</b>。手机号、身份证号由后端以脱敏形式（如 138****8888）输出。
    </NlNoticeBar>

    <section class="form">
      <div class="form__row">
        <label class="form__label">数据类型</label>
        <el-radio-group v-model="form.scope">
          <el-radio-button label="ORDER">订单</el-radio-button>
          <el-radio-button label="USER">用户</el-radio-button>
        </el-radio-group>
      </div>

      <!-- 用户导出才需要按角色筛选 -->
      <div v-if="form.scope === 'USER'" class="form__row">
        <label class="form__label">角色</label>
        <el-select v-model="form.role" placeholder="全部" clearable style="width: 200px">
          <el-option label="家属" value="FAMILY" />
          <el-option label="陪诊员" value="COMPANION" />
          <el-option label="老人" value="ELDER" />
          <el-option label="管理员" value="ADMIN" />
        </el-select>
      </div>

      <!-- 订单导出支持状态与关键字筛选 -->
      <template v-else>
        <div class="form__row">
          <label class="form__label">状态</label>
          <el-select v-model="form.status" placeholder="全部" clearable style="width: 200px">
            <el-option label="待接单" value="PENDING" />
            <el-option label="已接单" value="ACCEPTED" />
            <el-option label="服务中" value="IN_SERVICE" />
            <el-option label="已完成" value="COMPLETED" />
            <el-option label="已评价" value="REVIEWED" />
            <el-option label="已取消" value="CANCELLED" />
          </el-select>
        </div>
        <div class="form__row">
          <label class="form__label">关键字</label>
          <el-input v-model="form.keyword" placeholder="订单号 / 医院 / 姓名" clearable style="width: 200px" />
        </div>
      </template>

      <div class="form__row">
        <label class="form__label">时间范围</label>
        <el-date-picker
          v-model="form.range"
          type="daterange"
          range-separator="-"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          value-format="YYYY-MM-DD"
        />
      </div>
    </section>

    <div class="export-bar">
      <el-button
        type="primary"
        size="large"
        round
        class="export-bar__btn"
        :loading="exporting"
        @click="exportFile"
      >
        生成 Excel 导出
      </el-button>
    </div>
  </NlCard>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.form {
  display: flex;
  flex-direction: column;
  gap: $nl-space-4;

  &__row {
    display: flex;
    align-items: flex-start;
    gap: $nl-space-3;
  }

  &__label {
    flex-shrink: 0;
    width: 80px;
    padding-top: 8px;
    font-size: $nl-font-body;
    color: $nl-text-2;
    text-align: right;
  }
}

.export-bar {
  padding: $nl-space-5 0;

  &__btn {
    width: 100%;
  }
}
</style>
