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

    <!-- 校验统一走 el-form rules（最终复盘 FE-08）：手写 if + toast 时错误只在屏幕顶部闪一下，
         用户不知道该改哪个框；改成 rules 后错误直接落在字段下方，与其它 11 个页面同口径。 -->
    <el-form
      v-if="editable"
      ref="formRef"
      :model="form"
      :rules="rules"
      inline
      class="fee-items__form"
      @submit.prevent
    >
      <el-form-item label="类型" prop="itemType">
        <el-select v-model="form.itemType" style="width: 110px">
          <el-option label="代垫" value="ADVANCE" />
          <el-option label="服务费" value="SERVICE" />
        </el-select>
      </el-form-item>
      <el-form-item label="项目名" prop="itemName">
        <el-input v-model="form.itemName" maxlength="64" placeholder="如：心内科挂号费" style="width: 180px" />
      </el-form-item>
      <el-form-item label="金额" prop="amount">
        <el-input v-model="form.amount" placeholder="35.50" style="width: 110px" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="saving" @click="onSave">记一笔</el-button>
      </el-form-item>
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
const formRef = ref(null)

/**
 * 与后端 `FeeItemCreateDTO` 同口径：
 *   itemType  `@Pattern(ADVANCE|SERVICE)`
 *   itemName  `@NotBlank` + `@Size(max=64)`
 *   amount    `^\d{1,6}(\.\d{1,2})?$`（后端再校验 0.01 ~ 999999.99）
 *
 * ⚠️ 空 strings 要先 trim 再判 —— 全空格能绕过 `required`（FE-08 提到的漏项），
 *    所以 itemName 用自定义 validator 而不是裸 required。
 */
const rules = {
  itemType: [{ required: true, message: '请选择费用类型', trigger: 'change' }],
  itemName: [
    {
      validator: (_rule, value, cb) => {
        const v = (value || '').trim()
        if (!v) cb(new Error('请填写项目名'))
        else if (v.length > 64) cb(new Error('项目名不能超过 64 个字符'))
        else cb()
      },
      trigger: ['blur', 'change']
    }
  ],
  amount: [
    {
      validator: (_rule, value, cb) => {
        const v = (value || '').trim()
        if (!v) cb(new Error('请填写金额'))
        else if (!/^\d{1,6}(\.\d{1,2})?$/.test(v)) cb(new Error('金额须为两位小数，如 35.50'))
        else if (Number(v) <= 0) cb(new Error('金额必须大于 0'))
        else cb()
      },
      trigger: ['blur', 'change']
    }
  ]
}

async function load() {
  // 路由离开时父页的 orderId 会变 undefined（组件尚未卸载），
  // 这时不发请求 —— 否则会打出 /api/order/undefined/fee-items（V4V5 联调 DEF-1）
  if (props.orderId == null || props.orderId === 'undefined') {
    summary.value = null
    return
  }
  summary.value = await listFeeItems(props.orderId)
}

async function onSave() {
  if (saving.value) return
  // 一次性校验全部字段：错误各自落在对应输入框下方，不用一轮轮试
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    await createFeeItem(props.orderId, {
      itemType: form.itemType,
      itemName: form.itemName.trim(),
      amount: form.amount.trim(),
      occurredAt: formatNow()
    })
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
  /*
   * ⚠️ 令牌名必须是 styles/index.scss 里真实定义的那一套（--nl-text-1/2/3、--nl-primary-text…）。
   * 这里曾写成 `var(--nl-color-text-secondary, #909399)` —— 变量根本不存在，
   * 全靠 fallback 生效，实际是硬编码灰，老人模式切换时这一处不跟随（最终复盘 FE-11）。
   * 也不要再写 `var(--nl-*, 兜底值)`：兜底会让「写错令牌名」静默通过，
   * 改错的人以为改对了，只有截图对比才发现颜色没动。
   */
  color: var(--nl-text-3);
  &__total {
    color: var(--nl-primary-text);
    font-weight: 600;
  }
}
</style>
