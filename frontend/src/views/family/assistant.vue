<script setup>
/**
 * 家属端智能助手（M14 · V3.7）
 *
 * 能力边界（ADR-0020 决策 1）：
 *   · 只解答**平台服务规则**类问题；
 *   · 诊断 / 用药 / 剂量 / 病情判断由**服务端**固定拒答并引导线下就医；
 *     前端不做任何本地「补答」——那会绕过服务端的合规护栏。
 *
 * 三条硬边界在前端的体现：
 *   1. 助手**只填草稿，不自动下单**：命中 FILL_ORDER_DRAFT 时先展示卡片，
 *      用户点「去确认下单」才写 orderDraft 并跳转 —— 订单永远由用户确认。
 *   2. 推荐**只解释、不改写**：页面直接渲染服务端给的 answer，不二次加工分数。
 *   3. 页面**常驻免责声明**，且每条回答下方再带一行 —— 合规红线要求「页面必须出现」。
 *
 * 对话历史存在 sessionStorage（只存 {role, content}）：
 *   · **不存**订单 / 老人 / 手机号 / 身份证号等任何敏感信息；
 *   · 后端不落库、不建会话表（ADR-0020 决策 6）；
 *   · 页面关闭即失效。
 */
import { ref, computed, nextTick, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { NlPhoneShell, NlDesktopShell, NlCard, NlNoticeBar, NlEmpty } from '@/components'
import { useDevice } from '@/composables/useDevice'
import { useOrderDraftStore } from '@/store/modules/orderDraft'
import { chatAssistant } from '@/api/assistant'

const route = useRoute()
const router = useRouter()
const orderDraft = useOrderDraftStore()
const { isMobile } = useDevice()

/** sessionStorage key —— 只存对话文本 */
const HISTORY_KEY = 'nl_assistant_history'
/** 最多保留的历史轮数（后端 max-history-turns 同口径） */
const MAX_TURNS = 6

/** 消息列表：{ role, content, intent?, sources?, actions?, degraded? } */
const messages = ref([])
/** 输入框内容 */
const question = ref('')
/** 正在等回答 */
const sending = ref(false)
/** 消息区 DOM，用于滚到底 */
const listRef = ref(null)

/** 页面顶部的常驻免责声明 —— 合规红线要求「页面必须出现免责声明文案」 */
const DISCLAIMER = '本助手只解答平台服务规则问题，不提供医疗诊断与用药建议'

/** 引导问题（让家属一眼知道能问什么） */
const presets = [
  '陪诊费怎么算？',
  '下了单还能取消吗？',
  '我的订单到哪了？',
  '帮我找个合适的陪诊员'
]

/** 从订单详情页「问助手」进来时带上订单 ID */
const orderId = computed(() => {
  const raw = route.query.orderId
  return raw ? Number(raw) : null
})

/** 是否有可展示的草稿动作 */
function draftActionOf(message) {
  return (message.actions || []).find((a) => a.type === 'FILL_ORDER_DRAFT') || null
}

function navigateActionOf(message) {
  return (message.actions || []).find((a) => a.type === 'NAVIGATE') || null
}

/* ---------------- 历史（sessionStorage） ---------------- */

function loadHistory() {
  try {
    const raw = sessionStorage.getItem(HISTORY_KEY)
    if (!raw) return
    const parsed = JSON.parse(raw)
    if (Array.isArray(parsed)) {
      // 只取 role / content，其余字段不落本地
      messages.value = parsed
        .filter((m) => m && m.role && typeof m.content === 'string')
        .map((m) => ({ role: m.role, content: m.content }))
    }
  } catch {
    // 解析失败直接丢弃，不阻断页面
    messages.value = []
  }
}

function saveHistory() {
  try {
    const keep = messages.value
      .filter((m) => m.role === 'user' || m.role === 'assistant')
      .slice(-MAX_TURNS * 2)
      .map((m) => ({ role: m.role, content: m.content }))
    sessionStorage.setItem(HISTORY_KEY, JSON.stringify(keep))
  } catch {
    // 隐私模式 / 配额满：忽略，不影响使用
  }
}

/* ---------------- 提问 ---------------- */

async function scrollToBottom() {
  await nextTick()
  const el = listRef.value
  if (el) el.scrollTop = el.scrollHeight
}

async function send(text) {
  const content = (text ?? question.value).trim()
  if (!content || sending.value) return
  question.value = ''
  sending.value = true

  messages.value.push({ role: 'user', content })
  await scrollToBottom()

  // 传给后端的历史只取 user / assistant 两类，且只带文本
  const history = messages.value
    .slice(0, -1)
    .filter((m) => m.role === 'user' || m.role === 'assistant')
    .slice(-MAX_TURNS * 2)
    .map((m) => ({ role: m.role, content: m.content }))

  try {
    const data = await chatAssistant({
      question: content,
      history,
      orderId: orderId.value ?? undefined
    })
    messages.value.push({
      role: 'assistant',
      content: data?.answer || '暂时没有可用的回答，请稍后再试。',
      intent: data?.intent,
      sources: data?.sources || [],
      actions: data?.actions || [],
      degraded: data?.degraded === true
    })
  } catch (err) {
    // ⚠️ 业务错误（限流 10001 / 违规内容 10003 / 越权）后端给了**明确原因**，
    //    气泡必须显示真实原因。
    //    统一显示「助手暂时没有响应」会让用户以为服务挂了 ——
    //    而真实原因（提问过于频繁）就挂在旁边的提示条上，两处自相矛盾。
    //
    //    判据：拦截器给**业务错误**挂了数字 `code`（业务码；200 是成功，不会走到这里），
    //    HTTP 层错误（401/403/500）与网络异常的 `code` 是 axios 的**字符串**
    //    （'ERR_BAD_REQUEST' / 'ECONNABORTED'），那些场景的 err.message 是英文技术文案，
    //    仍然走中文兜底。
    //
    //    ⚠️ 判据是「数字」而不是「>= 1000」：通用业务码 400（参数错误）也在 1000 以下，
    //    曾经掉进兜底文案，把后端给的真实原因（例如「问题长度不能超过 500 字」）吞掉了。
    const code = err && err.code
    const isBusinessError = typeof code === 'number' && code > 0
    messages.value.push({
      role: 'assistant',
      content:
        isBusinessError && err.message
          ? err.message
          : '助手暂时没有响应，你的问题可以先看看「我的订单」或下方常见问题。'
    })
  } finally {
    sending.value = false
    saveHistory()
    await scrollToBottom()
  }
}

/* ---------------- 草稿回填 ---------------- */

/**
 * 把助手抽到的就诊信息写进下单草稿。
 *
 * ⚠️ 只在用户**点击按钮**后执行 —— 助手永不自动提交订单（ADR-0020 决策 5）。
 * ⚠️ 只写助手确实抽到的字段；抽不到的保留草稿里的原值，不覆盖。
 */
function applyDraft(message) {
  const draft = draftActionOf(message)
  if (!draft) return
  const payload = draft.payload || {}

  if (payload.elderId) {
    orderDraft.setElder(payload.elderId, payload.elderName || '')
  }
  if (payload.hospital || payload.department || payload.visitTime) {
    const current = orderDraft.hospital || {}
    orderDraft.setVisit(
      {
        name: payload.hospital ?? current.name,
        address: payload.address ?? current.address,
        longitude: current.longitude ?? null,
        latitude: current.latitude ?? null
      },
      payload.department ?? orderDraft.department,
      payload.visitTime ?? orderDraft.visitTime
    )
  }

  const nav = navigateActionOf(message)
  const target = nav?.payload?.path || (payload.elderId ? '/family/order/step2' : '/family/order/step1')
  ElMessage.success('已填好下单信息，请确认后提交')
  router.push(target)
}

/** 草稿卡片里展示的字段清单（只展示有的） */
function draftFields(message) {
  const payload = draftActionOf(message)?.payload || {}
  const rows = []
  if (payload.elderName) rows.push({ label: '就诊老人', value: payload.elderName })
  if (payload.hospital) rows.push({ label: '医院', value: payload.hospital })
  if (payload.department) rows.push({ label: '科室', value: payload.department })
  if (payload.visitTime) rows.push({ label: '就诊时间', value: payload.visitTime })
  return rows
}

onMounted(loadHistory)
</script>

<template>
  <!-- ==================== Mobile 形态 ==================== -->
  <NlPhoneShell
    v-if="isMobile"
    :nav="{ title: '智能助手', back: true }"
    :has-tabs="false"
  >
    <div class="asst">
      <NlNoticeBar tone="warning" class="asst__disclaimer">
        {{ DISCLAIMER }}
      </NlNoticeBar>

      <div ref="listRef" class="asst__list">
        <!-- 首屏空态：给出可问的方向 -->
        <NlEmpty
          v-if="!messages.length"
          type="empty"
          title="问我平台的事"
          description="下单、费用、取消、推荐陪诊员、评价投诉……都可以问"
        />

        <div v-for="(m, i) in messages" :key="i" class="asst__row" :class="`is-${m.role}`">
          <div class="asst__bubble">
            <!-- 回答用 white-space: pre-wrap 保留服务端给的换行（三段式结构靠换行分段） -->
            <p class="asst__text">{{ m.content }}</p>

            <!-- 降级提示：说明这条是标准答案原文，不是模型生成 -->
            <p v-if="m.degraded" class="asst__degraded">
              以下为平台标准答案原文（未经过大模型生成）
            </p>

            <!-- 医疗拒答：引导线下就医 -->
            <div v-if="m.intent === 'REFUSE_MEDICAL'" class="asst__refuse">
              <span>这些问题请咨询接诊医生</span>
              <el-link type="primary" underline="never" @click="router.push('/legal/service')">
                查看线下就医指引
              </el-link>
            </div>

            <!-- 草稿卡片：助手抽到了就诊信息 -->
            <div v-if="draftActionOf(m)" class="asst__draft">
              <div class="asst__draft-title">已为你填好下单信息</div>
              <ul class="asst__draft-list">
                <li v-for="row in draftFields(m)" :key="row.label">
                  <span class="asst__draft-label">{{ row.label }}</span>
                  <span class="asst__draft-value">{{ row.value }}</span>
                </li>
              </ul>
              <el-button type="primary" size="small" round @click="applyDraft(m)">
                去确认下单
              </el-button>
            </div>

            <!-- 依据引用：可展开 -->
            <details v-if="m.sources && m.sources.length" class="asst__sources">
              <summary>依据</summary>
              <ul>
                <li v-for="s in m.sources" :key="s">{{ s }}</li>
              </ul>
            </details>

            <!-- 每条回答下方再带一行免责 -->
            <p class="asst__foot">本助手不提供医疗诊断与用药建议</p>
          </div>
        </div>
      </div>

      <div class="asst__input">
        <el-input
          v-model="question"
          type="textarea"
          :rows="2"
          resize="none"
          maxlength="500"
          show-word-limit
          placeholder="说说你的问题，例如：陪诊费怎么算？"
          @keydown.enter.exact.prevent="send()"
        />
        <el-button type="primary" round :loading="sending" @click="send()">发送</el-button>
      </div>
    </div>
  </NlPhoneShell>

  <!-- ==================== Desktop 形态（ADR-0007） ==================== -->
  <NlDesktopShell v-else>
    <div class="asst">
      <NlCard>
        <template #title>
          <div class="block-head">
            <span class="nl-h2">智能助手</span>
          </div>
        </template>

        <NlNoticeBar tone="warning" class="asst__disclaimer">
          {{ DISCLAIMER }}
        </NlNoticeBar>

        <div ref="listRef" class="asst__list">
          <NlEmpty
            v-if="!messages.length"
            type="empty"
            title="问我平台的事"
            description="下单、费用、取消、推荐陪诊员、评价投诉……都可以问"
          />

          <div v-for="(m, i) in messages" :key="i" class="asst__row" :class="`is-${m.role}`">
            <div class="asst__bubble">
              <p class="asst__text">{{ m.content }}</p>

              <p v-if="m.degraded" class="asst__degraded">
                以下为平台标准答案原文（未经过大模型生成）
              </p>

              <div v-if="m.intent === 'REFUSE_MEDICAL'" class="asst__refuse">
                <span>这些问题请咨询接诊医生</span>
                <el-link type="primary" underline="never" @click="router.push('/legal/service')">
                  查看线下就医指引
                </el-link>
              </div>

              <div v-if="draftActionOf(m)" class="asst__draft">
                <div class="asst__draft-title">已为你填好下单信息</div>
                <ul class="asst__draft-list">
                  <li v-for="row in draftFields(m)" :key="row.label">
                    <span class="asst__draft-label">{{ row.label }}</span>
                    <span class="asst__draft-value">{{ row.value }}</span>
                  </li>
                </ul>
                <el-button type="primary" size="small" round @click="applyDraft(m)">
                  去确认下单
                </el-button>
              </div>

              <details v-if="m.sources && m.sources.length" class="asst__sources">
                <summary>依据</summary>
                <ul>
                  <li v-for="s in m.sources" :key="s">{{ s }}</li>
                </ul>
              </details>

              <p class="asst__foot">本助手不提供医疗诊断与用药建议</p>
            </div>
          </div>
        </div>

        <div class="asst__presets">
          <el-button
            v-for="p in presets"
            :key="p"
            size="small"
            round
            plain
            @click="send(p)"
          >
            {{ p }}
          </el-button>
        </div>

        <div class="asst__input">
          <el-input
            v-model="question"
            type="textarea"
            :rows="2"
            resize="none"
            maxlength="500"
            show-word-limit
            placeholder="说说你的问题，例如：陪诊费怎么算？"
            @keydown.enter.exact.prevent="send()"
          />
          <el-button type="primary" round :loading="sending" @click="send()">发送</el-button>
        </div>
      </NlCard>
    </div>
  </NlDesktopShell>
</template>

<style scoped>
.asst {
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-3);
}

.asst__disclaimer {
  margin-bottom: var(--nl-space-2);
}

.asst__list {
  flex: 1;
  min-height: 320px;
  max-height: 58vh;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: var(--nl-gap-item);
  padding: var(--nl-space-1) 0;
}

.asst__row {
  display: flex;
}

.asst__row.is-user {
  justify-content: flex-end;
}

.asst__bubble {
  max-width: 86%;
  padding: var(--nl-space-3);
  border-radius: var(--nl-radius-card);
  background: var(--nl-bg-card);
  border: var(--nl-border-width) solid var(--nl-border);
  color: var(--nl-text-1);
  font-size: var(--nl-font-body);
  line-height: var(--nl-lh-body);
}

.asst__row.is-user .asst__bubble {
  background: var(--nl-primary-ghost);
  border-color: var(--nl-primary-light);
}

.asst__row.is-assistant .asst__bubble {
  max-width: 100%;
}

/* 服务端的回答带换行（三段式结构靠换行分段），所以保留 pre-wrap */
.asst__text {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}

.asst__degraded {
  margin: var(--nl-space-2) 0 0;
  font-size: var(--nl-font-caption);
  color: var(--nl-info-text);
  background: var(--nl-info-bg);
  padding: var(--nl-space-1) var(--nl-space-2);
  border-radius: var(--nl-radius-chip);
  display: inline-block;
}

.asst__refuse {
  margin-top: var(--nl-space-2);
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--nl-space-2);
  font-size: var(--nl-font-caption);
  color: var(--nl-warning-text);
}

.asst__draft {
  margin-top: var(--nl-space-3);
  padding: var(--nl-space-3);
  border-radius: var(--nl-radius-card);
  background: var(--nl-bg-sunken);
  border: var(--nl-border-width) solid var(--nl-border);
}

.asst__draft-title {
  font-size: var(--nl-font-h3);
  color: var(--nl-text-1);
  margin-bottom: var(--nl-space-2);
}

.asst__draft-list {
  list-style: none;
  margin: 0 0 var(--nl-space-2);
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-1);
  font-size: var(--nl-font-caption);
}

.asst__draft-label {
  color: var(--nl-text-3);
  margin-right: var(--nl-space-2);
}

.asst__draft-value {
  color: var(--nl-text-1);
}

.asst__sources {
  margin-top: var(--nl-space-2);
  font-size: var(--nl-font-caption);
  color: var(--nl-text-3);
}

.asst__sources summary {
  cursor: pointer;
}

.asst__sources ul {
  margin: var(--nl-space-1) 0 0;
  padding-left: var(--nl-space-4);
}

.asst__foot {
  margin: var(--nl-space-2) 0 0;
  font-size: var(--nl-font-micro);
  color: var(--nl-text-3);
}

.asst__presets {
  display: flex;
  flex-wrap: wrap;
  gap: var(--nl-space-2);
  margin-bottom: var(--nl-space-2);
}

.asst__input {
  display: flex;
  align-items: flex-end;
  gap: var(--nl-space-2);
}

.asst__input .el-textarea {
  flex: 1;
}
</style>
