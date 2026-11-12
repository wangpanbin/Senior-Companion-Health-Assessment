<script setup>
/**
 * W-13 参数配置（V4 · P2-3 运营参数可调化）
 *
 * 数据来自 sys_dict（dict_type=OPS_PARAM），只有 4 个运营阈值：
 * 漏服判定阈值 / 催单最小间隔 / 单次导出条数上限 / 漏服短信单用户日上限。
 *
 * 三条规则：
 *   1. 页面只做展示与修改，**默认值来自 application.yml**，前端不持有一份副本；
 *   2. 修改后后端在事务提交时刷新缓存，本实例下一次业务调用即生效，无需重启；
 *   3. 每次修改都会写 admin_oper_log，页面下方的「变更留痕」只读展示。
 *
 * 合规：本页只调数值，不承载任何诊断 / 用药建议类文案。
 */
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { NlCard, NlSkeleton, NlEmpty, NlAdminTable } from '@/components'
import { getOperationParams, updateOperationParam, listOperationParamLogs } from '@/api/admin'
import { formatDateTime } from '@/utils/format'

const params = ref([])
const loading = ref(false)
const loadError = ref(false)

const logs = ref([])
const logsLoading = ref(false)

const dialogVisible = ref(false)
const saving = ref(false)
const editing = ref({ code: '', label: '', value: 0, min: 1, max: 100, unit: '', remark: '' })

const paramColumns = [
  { key: 'label', label: '参数', minWidth: 150 },
  { key: 'code', label: '键', minWidth: 240 },
  { key: 'value', label: '当前值', width: 110, align: 'right' },
  { key: 'defaultValue', label: '默认值', width: 100, align: 'right' },
  {
    key: 'range',
    label: '可调范围',
    minWidth: 140,
    formatter: (_value, row) => `${row.min} – ${row.max} ${row.unit}`
  },
  { key: 'description', label: '说明', minWidth: 260 },
  { key: 'updateTime', label: '最近更新', width: 170, formatter: (value) => formatDateTime(value) }
]
const paramMobileFields = ['label', 'value', 'range', 'description', 'updateTime']

const logColumns = [
  { key: 'operTime', label: '时间', width: 170, formatter: (value) => formatDateTime(value) },
  { key: 'operatorName', label: '操作人', width: 110 },
  { key: 'targetDesc', label: '参数键', minWidth: 240 },
  { key: 'beforeStatus', label: '旧值', width: 90 },
  { key: 'afterStatus', label: '新值', width: 90 },
  { key: 'remark', label: '备注', minWidth: 240 }
]
const logMobileFields = ['operTime', 'operatorName', 'targetDesc', 'afterStatus', 'remark']

async function loadParams() {
  loading.value = true
  try {
    params.value = (await getOperationParams()) || []
    loadError.value = false
  } catch {
    params.value = []
    loadError.value = true
  } finally {
    loading.value = false
  }
}

async function loadLogs() {
  logsLoading.value = true
  try {
    const data = await listOperationParamLogs({ page: 1, size: 10 })
    logs.value = data?.records || []
  } catch {
    // 留痕加载失败不影响参数列表；请求层已提示
    logs.value = []
  } finally {
    logsLoading.value = false
  }
}

function openEdit(row) {
  editing.value = {
    code: row.code,
    label: row.label,
    value: row.value,
    min: row.min,
    max: row.max,
    unit: row.unit,
    remark: ''
  }
  dialogVisible.value = true
}

async function submitEdit() {
  if (saving.value) return
  const value = Number(editing.value.value)
  if (!Number.isInteger(value) || value < editing.value.min || value > editing.value.max) {
    ElMessage.error(`取值必须是 ${editing.value.min} 到 ${editing.value.max} 之间的整数`)
    return
  }

  saving.value = true
  try {
    await updateOperationParam(editing.value.code, {
      value,
      remark: editing.value.remark.trim() || undefined
    })
    ElMessage.success('参数已更新，下一次业务调用即生效')
    dialogVisible.value = false
    await Promise.all([loadParams(), loadLogs()])
  } catch {
    // 请求层拦截器已提示（含越权 / 越界原因）
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  loadParams()
  loadLogs()
})
</script>

<template>
  <NlCard title="参数配置" plain>
    <p class="nl-caption nl-text-muted">
      仅管理员可修改。保存后写入数据字典并落操作日志，本实例下一次业务调用即生效，无需重启或发版；
      其余实例最多等待一个缓存周期（默认 60 秒）收敛。字典缺失或读取失败时按 application.yml 默认值运行。
    </p>

    <NlSkeleton v-if="loading" :count="4" />

    <NlEmpty
      v-else-if="loadError"
      type="network"
      title="参数列表加载失败"
      description="当前无法读取数据字典，可稍后重试"
      action-text="重试"
      @action="loadParams"
    />

    <NlAdminTable
      v-else
      :data="params"
      :columns="paramColumns"
      :mobile-fields="paramMobileFields"
      :action-width="100"
      empty-text="暂无参数"
    >
      <template #cell-value="{ row }">
        <span class="param-value">{{ row.value }}</span>
        <el-tag v-if="row.usingDefault" size="small" type="info" class="param-tag">默认</el-tag>
      </template>
      <template #actions="{ row }">
        <el-button text size="small" type="primary" @click="openEdit(row)">编辑</el-button>
      </template>
    </NlAdminTable>

    <section class="logs">
      <h3 class="logs__title">变更留痕（最近 10 条）</h3>
      <NlSkeleton v-if="logsLoading" :count="3" />
      <NlEmpty
        v-else-if="!logs.length"
        type="empty"
        title="暂无变更记录"
        description="参数修改后会自动写入操作日志"
      />
      <NlAdminTable
        v-else
        :data="logs"
        :columns="logColumns"
        :mobile-fields="logMobileFields"
        empty-text="暂无变更记录"
      />
    </section>
  </NlCard>

  <el-dialog v-model="dialogVisible" title="修改参数" width="520px" destroy-on-close>
    <el-form label-width="90px">
      <el-form-item label="参数">{{ editing.label }}</el-form-item>
      <el-form-item label="键">
        <code class="param-code">{{ editing.code }}</code>
      </el-form-item>
      <el-form-item label="可调范围">
        {{ editing.min }} – {{ editing.max }} {{ editing.unit }}
      </el-form-item>
      <el-form-item label="新值" required>
        <el-input-number
          v-model="editing.value"
          :min="editing.min"
          :max="editing.max"
          :step="1"
          step-strictly
          controls-position="right"
          style="width: 200px"
        />
      </el-form-item>
      <el-form-item label="备注">
        <el-input
          v-model="editing.remark"
          type="textarea"
          :rows="3"
          maxlength="200"
          show-word-limit
          placeholder="填写修改原因，便于审计"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submitEdit">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
.param-value {
  font-family: var(--nl-font-num);
  font-weight: 600;
}

.param-tag {
  margin-left: var(--nl-space-1);
}

.param-code {
  color: var(--nl-text-2);
  font-size: var(--nl-font-caption);
  word-break: break-all;
}

.logs {
  margin-top: var(--nl-space-5);
  padding-top: var(--nl-space-4);
  border-top: 1px dashed var(--nl-divider);

  &__title {
    margin: 0 0 var(--nl-space-3);
    font-size: var(--nl-font-h3);
    color: var(--nl-text-1);
  }
}
</style>
