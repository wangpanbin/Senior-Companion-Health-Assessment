<script setup>
/**
 * W-07 数据导出
 *
 * - 筛选条件：日期范围 + 角色 + 状态
 * - 字段勾选
 * - 导出格式：Excel（EasyExcel 后端生成）
 * - 合规：脱敏列强制勾选，不允许导出明文
 */
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { NlCard, NlNoticeBar } from '@/components'

const form = ref({
  range: null,
  role: '',
  scope: 'ORDER',
  fields: [
    { key: 'orderNo',       label: '订单号',         required: true,  fixed: true },
    { key: 'elder',         label: '就诊人',         required: false },
    { key: 'hospital',      label: '医院',           required: false },
    { key: 'companion',     label: '陪诊员',         required: false },
    { key: 'fee',           label: '服务费',         required: false },
    { key: 'status',        label: '状态',           required: false },
    { key: 'createdAt',     label: '创建时间',       required: false },
    { key: 'phoneMasked',   label: '手机号（脱敏）', required: true,  fixed: true },
    { key: 'idCardMasked',  label: '身份证号（脱敏）', required: false, fixed: true }
  ]
})

function exportFile() {
  ElMessageBox.confirm(
    '导出的数据将按筛选条件实时查询；敏感字段将以脱敏形式输出，不会泄露。',
    '确认导出',
    { confirmButtonText: '导出 Excel' }
  ).then(() => {
    ElMessage.success('已生成导出任务，完成后将通过站内信通知')
  }).catch(() => {})
}
</script>

<template>
  <NlCard title="数据导出" plain>
    <NlNoticeBar tone="warning">
      密码 / 完整手机号 / 身份证号明文**不允许导出**。手机号、身份证号将以脱敏形式（如 138****8888）输出。
    </NlNoticeBar>

    <section class="form">
      <div class="form__row">
        <label class="form__label">数据类型</label>
        <el-radio-group v-model="form.scope">
          <el-radio-button label="ORDER">订单</el-radio-button>
          <el-radio-button label="USER">用户</el-radio-button>
          <el-radio-button label="REVIEW">评价</el-radio-button>
          <el-radio-button label="MEDICATION">用药任务</el-radio-button>
        </el-radio-group>
      </div>
      <div class="form__row">
        <label class="form__label">角色</label>
        <el-select v-model="form.role" placeholder="全部" clearable style="width: 200px">
          <el-option label="家属" value="FAMILY" />
          <el-option label="陪诊员" value="COMPANION" />
          <el-option label="老人" value="ELDER" />
          <el-option label="管理员" value="ADMIN" />
        </el-select>
      </div>
      <div class="form__row">
        <label class="form__label">时间范围</label>
        <el-date-picker v-model="form.range" type="daterange" range-separator="-" start-placeholder="开始日期" end-placeholder="结束日期" />
      </div>
      <div class="form__row">
        <label class="form__label">导出字段</label>
        <div class="fields">
          <el-checkbox
            v-for="f in form.fields"
            :key="f.key"
            v-model="f.checked"
            :disabled="f.fixed"
          >
            {{ f.label }}
            <span v-if="f.fixed" class="nl-caption nl-text-muted">（脱敏必选）</span>
          </el-checkbox>
        </div>
      </div>
    </section>

    <div class="export-bar">
      <el-button type="primary" size="large" round class="export-bar__btn" @click="exportFile">
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

.fields {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: $nl-space-3;
  padding: $nl-space-3;
  background: var(--nl-bg-sunken);
  border-radius: $nl-radius-input;
}

.export-bar {
  padding: $nl-space-5 0;

  &__btn {
    width: 100%;
  }
}
</style>
