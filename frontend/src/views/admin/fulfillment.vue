<script setup>
/**
 * 履约风险中心（V3.1 · T6 / W-10）
 *
 * 数据来源：
 *   GET /api/admin/fulfillment/summary     四档计数（整口径，只统计待接单）
 *   GET /api/admin/fulfillment/risk-list   风险订单列表（分页 + 等级筛选，排序固定）
 *   GET /api/order/{id}                    行内「查看详情」（管理员属相关方，走既有详情接口）
 *
 * 口径要点：
 *   1. 计数与列表是两个接口、**同一份候选集**（只统计待接单）。已接单的订单风险已回落，
 *      计进去会凭空放大问题；
 *   2. 四张卡显示的是整口径数字，**筛选只影响列表**。否则管理员一按等级筛选，
 *      「正常等待」那张卡就归零，看板立刻失去参考价值；
 *   3. 等级与文案一律由后端下发（fulfillmentRisk / fulfillmentRiskLabel），
 *      前端只做「等级 → 色调」的映射，与家属端 utils/fulfillment 同一套口径，
 *      避免两端对同一等级显示不同结论；
 *   4. 列表里姓名与订单号是**脱敏**的；要看全名请点「查看详情」——
 *      详情接口返回全名，那是相关方豁免，只对已通过归属校验的详情生效。
 */
import { ref, onMounted } from 'vue'
import { NlCard, NlSkeleton, NlEmpty, NlAdminTable } from '@/components'
import { getFulfillmentSummary, listFulfillmentRisks } from '@/api/admin'
import { getOrder } from '@/api/order'
import { formatDateTime } from '@/utils/format'
import { fulfillmentTone } from '@/utils/fulfillment'

/** 四档卡片定义：顺序即展示顺序（从最坏到最好，管理员先看到需要动手的） */
const CARDS = [
  { key: 'manualRequired', risk: 'MANUAL_REQUIRED', label: '需要人工介入' },
  { key: 'urgent', risk: 'URGENT', label: '履约风险较高' },
  { key: 'warning', risk: 'WARNING', label: '等待时间较长' },
  { key: 'normal', risk: 'NORMAL', label: '正常等待' }
]

const summary = ref(null)
const summaryLoading = ref(false)
const summaryError = ref(false)

const list = ref([])
const listLoading = ref(false)
const listError = ref(false)
const total = ref(0)
const page = ref(1)
const size = ref(10)
const risk = ref('')

const riskOptions = [{ key: '', label: '全部' }, ...CARDS.map((c) => ({ key: c.risk, label: c.label }))]

function toneOf(riskName) {
  return fulfillmentTone(riskName)
}

async function loadSummary() {
  summaryLoading.value = true
  try {
    summary.value = await getFulfillmentSummary()
    summaryError.value = false
  } catch {
    summary.value = null
    summaryError.value = true
  } finally {
    summaryLoading.value = false
  }
}

async function loadList() {
  listLoading.value = true
  try {
    const params = { page: page.value, size: size.value }
    if (risk.value) params.risk = risk.value
    const data = await listFulfillmentRisks(params)
    list.value = data?.records || []
    total.value = data?.total || 0
    listError.value = false
  } catch {
    list.value = []
    total.value = 0
    listError.value = true
  } finally {
    listLoading.value = false
  }
}

// 切换筛选回到第一页：停留在旧页码会让人以为「这个等级一单都没有」
function onRiskChange() {
  page.value = 1
  loadList()
}

function onPageChange(p) {
  page.value = p
  loadList()
}

/** 列定义：desktop 全展示，mobile 取 mobileFields 顺序 */
const columns = [
  { key: 'orderNo', label: '订单号', width: 170 },
  { key: 'elderName', label: '就诊人', width: 100 },
  {
    key: 'hospital',
    label: '陪诊医院',
    minWidth: 180,
    formatter: (v, row) => (v ? `${v} · ${row.department || ''}` : '')
  },
  { key: 'visitTime', label: '就诊时间', width: 160, formatter: (v) => formatDateTime(v) },
  { key: 'fulfillmentRiskLabel', label: '风险', width: 120 },
  { key: 'waitMinutes', label: '已等待', width: 90, formatter: (v) => `${v} 分钟` },
  { key: 'urgeCount', label: '催单次数', width: 90, align: 'right' },
  { key: 'adminCoordinated', label: '人工介入', width: 90 }
]
const mobileFields = ['orderNo', 'elderName', 'fulfillmentRiskLabel', 'waitMinutes']

/* ==================== 行内「查看详情」 ==================== */

const detailVisible = ref(false)
const detailLoading = ref(false)
const detailError = ref(false)
const detail = ref(null)

async function openDetail(row) {
  detailVisible.value = true
  detailLoading.value = true
  detailError.value = false
  detail.value = null
  try {
    // 走既有订单详情接口：管理员属于相关方（requireInvolved 放行 ADMIN），
    // 与订单管理页看到的是同一份详情口径
    detail.value = await getOrder(row.orderId)
  } catch {
    detailError.value = true
  } finally {
    detailLoading.value = false
  }
}

onMounted(() => {
  loadSummary()
  loadList()
})
</script>

<template>
  <NlCard title="履约风险中心" plain>
    <!-- ==================== 四档计数 ==================== -->
    <NlSkeleton v-if="summaryLoading" :count="1" class="counters-skeleton" />

    <NlEmpty
      v-else-if="summaryError"
      type="network"
      title="风险计数加载失败"
      action-text="重试"
      @action="loadSummary"
    />

    <section v-else class="counters">
      <div v-for="c in CARDS" :key="c.key" class="counters__item" :class="`is-${toneOf(c.risk).key}`">
        <span class="counters__dot" aria-hidden="true" />
        <div class="counters__body">
          <div class="counters__num is-num">{{ summary?.[c.key] ?? 0 }}</div>
          <div class="counters__label">{{ c.label }}</div>
        </div>
      </div>
    </section>

    <!-- ==================== 筛选 + 列表 ==================== -->
    <section class="filters">
      <el-radio-group v-model="risk" @change="onRiskChange">
        <el-radio-button v-for="o in riskOptions" :key="o.key" :value="o.key">
          {{ o.label }}
        </el-radio-button>
      </el-radio-group>
    </section>

    <NlSkeleton v-if="listLoading" :count="4" />

    <NlEmpty
      v-else-if="listError"
      type="network"
      title="风险列表加载失败"
      action-text="重试"
      @action="loadList"
    />

    <NlAdminTable
      v-else
      :data="list"
      :columns="columns"
      :mobile-fields="mobileFields"
      row-key="orderId"
      empty-text="当前没有符合条件的风险订单"
    >
      <!-- 风险等级：颜色只走色点，文案用后端下发的中文 -->
      <template #cell-fulfillmentRiskLabel="{ row }">
        <span class="risk-cell" :class="`is-${toneOf(row.fulfillmentRisk).key}`">
          <span class="risk-cell__dot" aria-hidden="true" />
          {{ row.fulfillmentRiskLabel }}
        </span>
      </template>

      <template #cell-adminCoordinated="{ row }">
        <el-tag size="small" effect="plain" :type="row.adminCoordinated ? 'warning' : 'info'">
          {{ row.adminCoordinated ? '已协调' : '未协调' }}
        </el-tag>
      </template>

      <template #actions="{ row }">
        <el-button text size="small" @click="openDetail(row)">查看详情</el-button>
      </template>
    </NlAdminTable>

    <div v-if="!listLoading && !listError && total > 0" class="pager">
      <el-pagination
        layout="total, prev, pager, next"
        :total="total"
        :page-size="size"
        :current-page="page"
        background
        @current-change="onPageChange"
      />
    </div>
  </NlCard>

  <!-- ==================== 订单详情弹窗 ==================== -->
  <el-dialog v-model="detailVisible" title="订单详情" width="560px">
    <NlSkeleton v-if="detailLoading" :count="3" />

    <NlEmpty v-else-if="detailError" type="network" title="订单详情加载失败" />

    <ul v-else-if="detail" class="detail">
      <li><span>订单号</span><span class="is-num">{{ detail.orderNo }}</span></li>
      <li><span>当前状态</span><span>{{ detail.statusLabel }}</span></li>
      <li><span>就诊人</span><span>{{ detail.elderName }}</span></li>
      <li><span>陪诊医院</span><span>{{ detail.hospital }} · {{ detail.department }}</span></li>
      <li><span>就诊时间</span><span class="is-num">{{ formatDateTime(detail.visitTime) }}</span></li>
      <li><span>医院地址</span><span>{{ detail.address }}</span></li>
      <li><span>下单时间</span><span class="is-num">{{ formatDateTime(detail.createTime) }}</span></li>
      <li v-if="detail.remark"><span>家属备注</span><span>{{ detail.remark }}</span></li>
      <li class="detail__risk">
        <span>履约状态</span>
        <span>
          {{ detail.fulfillmentRiskLabel }}
          <template v-if="detail.waitMinutes"> · 已等待 {{ detail.waitMinutes }} 分钟</template>
          <template v-if="detail.urgeCount"> · 已催 {{ detail.urgeCount }} 次</template>
        </span>
      </li>
    </ul>

    <template #footer>
      <el-button @click="detailVisible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.counters-skeleton {
  padding: 0 var(--nl-space-4);
}

.counters {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--nl-space-3);
  margin-bottom: var(--nl-space-4);

  &__item {
    --tone: var(--nl-text-2);
    display: flex;
    gap: var(--nl-space-2);
    align-items: center;
    padding: var(--nl-space-3);
    background: var(--nl-bg-card);
    border: 1px solid var(--nl-border);
    border-radius: var(--nl-radius-card);

    &.is-warning {
      --tone: var(--nl-warning-text);
    }

    &.is-urgent {
      --tone: var(--nl-danger-text);
    }

    &.is-manual {
      --tone: var(--nl-purple-text);
    }
  }

  &__dot {
    flex-shrink: 0;
    width: 10px;
    height: 10px;
    border-radius: 50%;
    background: var(--tone);
  }

  &__num {
    font-size: var(--nl-font-h1);
    font-weight: 700;
    line-height: 1.1;
    color: var(--nl-text-1);
  }

  &__label {
    margin-top: 2px;
    font-size: var(--nl-font-caption);
    color: var(--nl-text-2);
  }
}

.filters {
  display: flex;
  gap: var(--nl-space-2);
  margin-bottom: var(--nl-space-3);
}

.risk-cell {
  --tone: var(--nl-text-2);
  display: inline-flex;
  gap: 6px;
  align-items: center;

  &.is-warning {
    --tone: var(--nl-warning-text);
  }

  &.is-urgent {
    --tone: var(--nl-danger-text);
  }

  &.is-manual {
    --tone: var(--nl-purple-text);
  }

  &__dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: var(--tone);
  }

  color: var(--tone);
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--nl-space-3);
}

.detail {
  padding: 0;
  margin: 0;
  list-style: none;

  li {
    display: flex;
    gap: var(--nl-space-3);
    align-items: baseline;
    padding: var(--nl-space-2) 0;
    border-bottom: 1px solid var(--nl-divider);

    &:last-child {
      border-bottom: none;
    }

    > span:first-child {
      flex-shrink: 0;
      width: 84px;
      font-size: var(--nl-font-caption);
      color: var(--nl-text-2);
    }

    > span:last-child {
      flex: 1;
      color: var(--nl-text-1);
    }
  }

  &__risk {
    background: var(--nl-bg-sunken);
    margin: 0 calc(var(--nl-space-3) * -1);
    padding-left: var(--nl-space-3);
    padding-right: var(--nl-space-3);
    border-radius: var(--nl-radius-chip);
    border-bottom: none;
  }
}
</style>
