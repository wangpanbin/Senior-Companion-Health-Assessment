<template>
  <div class="fee-items">
    <el-alert v-if="summary?.fallbackNotice" :title="summary.fallbackNotice" type="info" :closable="false" />
    <template v-else>
      <el-table :data="summary?.items || []" size="small">
        <el-table-column prop="itemName" label="项目" min-width="140" />
        <el-table-column prop="itemTypeLabel" label="类型" width="90">
          <template #default="{ row }">
            <el-tag :type="row.itemType === 'ADVANCE' ? 'warning' : 'primary'" size="small">
              {{ row.itemTypeLabel }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="amount" label="金额（元）" width="110" align="right" />
        <el-table-column prop="occurredAt" label="发生时间" width="170" />
        <el-table-column v-if="editable" label="操作" width="80" align="center">
          <template #default="{ row }">
            <el-button link type="danger" @click="emit('remove', row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="fee-items__totals">
        <span>代垫合计：¥{{ summary?.advanceTotal || '0.00' }}</span>
        <span>服务费合计：¥{{ summary?.serviceTotal || '0.00' }}</span>
        <span class="fee-items__total">总计：¥{{ summary?.total || '0.00' }}</span>
      </div>
    </template>

    <el-form v-if="editable" inline class="fee-items__form" @submit.prevent>
      <el-form-item label="类型">
        <el-select v-model="form.itemType" style="width: 110px">
          <el-option label="代垫" value="ADVANCE" />
          <el-option label="服务费" value="SERVICE" />
        </el-select>
      </el-form-item>
      <el-form-item label="项目名">
        <el-input v-model="form.itemName" maxlength="64" placeholder="如：心内科挂号费" style="width: 180px" />
      </el-form-item>
      <el-form-item label="金额">
        <el-input v-model="form.amount" placeholder="35.50" style="width: 110px" />
      </el-form-item>
      <el-button type="primary" :loading="saving" @click="onSave">记一笔</el-button>
    </el-form>
  </div>
</template>

<script setup>
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { listFeeItems, createFeeItem } from '@/api/order'

/** 明细面板：editable=false 只读（家属详情页），true 带记账表单（陪诊员端） */
const props = defineProps({
  orderId: { type: [Number, String], required: true },
  editable: { type: Boolean, default: false }
})
const emit = defineEmits(['remove', 'changed'])

const summary = ref(null)
const saving = ref(false)
const form = reactive({ itemType: 'ADVANCE', itemName: '', amount: '' })

async function load() {
  summary.value = await listFeeItems(props.orderId)
}

async function onSave() {
  if (!form.itemName.trim() || !/^\d{1,6}(\.\d{1,2})?$/.test(form.amount)) {
    ElMessage.warning('请填写项目名与两位小数金额')
    return
  }
  saving.value = true
  try {
    await createFeeItem(props.orderId, { ...form, occurredAt: formatNow() })
    ElMessage.success('已记录')
    form.itemName = ''
    form.amount = ''
    await load()
    emit('changed')
  } finally {
    saving.value = false
  }
}

function formatNow() {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:00`
}

watch(() => props.orderId, load, { immediate: true })
</script>

<style scoped lang="scss">
.fee-items__totals {
  display: flex;
  gap: 16px;
  margin-top: 8px;
  color: var(--nl-color-text-secondary, #909399);
  &__total {
    color: var(--nl-color-primary, var(--el-color-primary));
    font-weight: 600;
  }
}
</style>
