<script setup>
/**
 * 产品反馈（M-34 · V6 · docs/api/15-product-feedback.md）
 *
 * 两段式单页：上半「提交反馈」，下半「我的反馈」。
 *
 * ## 与「我要投诉」（order-complaint.vue）的三处刻意不同
 *
 * 1. **没有订单上下文**。这条通道与订单完全解耦，所以没有 orderId 入参、
 *    没有订单摘要卡、也没有「页面展示的订单是否就是 URL 指向的订单」那类竞态判断。
 * 2. **入参只过敏感词**。用户写「希望用药页能提醒别吃错」是**正当建议**，
 *    服务端刻意不套医疗红线词表；前端因此也不要自己加一层「用药」「剂量」的本地拦截，
 *    否则同一个词会在两端被两套口径判。
 * 3. **截图 3 张而不是 6 张**。产品截图是「说明问题用的插图」，不是仲裁证据；
 *    3 张够说清楚，也避免用户把整屏相册传上来。
 *
 * ## 三条前端硬规矩（都踩过）
 *
 * - **in-flight 锁必须在第一个 await 之前同步置位**。置位晚于 await 的话，
 *   同 tick 连点会一起穿过 await，锁形同虚设 —— 用户看到「成功 + 报错」双提示。
 * - **字段级错误优先于全局 toast**。6003（敏感词）落在正文字段下方：
 *   拦截器的顶部 toast 与页面输入框隔了半屏，用户改完还得滚回去看提示。
 * - **老人端无入口**。路由 roles 只写 FAMILY / COMPANION；本页不做角色分支。
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { NlPageShell, NlCard, NlStatusChip, NlNoticeBar, NlEmpty, NlSkeleton } from '@/components'
import { createFeedback, listMyFeedback } from '@/api/feedback'
import { uploadFile } from '@/api/file'
import { formatDateTime, FEEDBACK_TYPE_TEXT } from '@/utils/format'

/**
 * 反馈分类（取自 utils/format.js 的 FEEDBACK_TYPE_TEXT）。
 *
 * <p>不在本页另抄一份：格式化的那份是 {@code FEEDBACK_STATUS_TEXT /
 * FEEDBACK_TYPE_TEXT}，抄一份就会两处各改一次、迟早对不上。</p>
 */
const FEEDBACK_TYPES = Object.entries(FEEDBACK_TYPE_TEXT).map(([value, label]) => ({ value, label }))

const MAX_IMAGES = 3
const MAX_IMAGE_BYTES = 10 * 1024 * 1024

const form = reactive({ type: '', content: '', images: [] })
const formRef = ref(null)

/**
 * 表单校验：错误直接落在对应字段下方，不用一次性 toast。
 *
 * 正文用自定义 validator 而不是 min/max，是为了保留「先 trim 再判长度」的口径 ——
 * 纯空格在用户看来是「没填」，用 min 会因原始长度不足而放过。
 */
const rules = {
  type: [{ required: true, message: '请选择反馈分类', trigger: 'change' }],
  content: [
    {
      required: true,
      validator: (_rule, value, cb) => {
        const text = (value || '').trim()
        if (text.length < 10) {
          cb(new Error('请至少写 10 个字，说明您希望改进什么'))
          return
        }
        if (text.length > 1000) {
          cb(new Error('反馈内容不能超过 1000 个字'))
          return
        }
        cb()
      },
      trigger: 'blur'
    }
  ]
}

const submitting = ref(false)
const uploading = ref(false)

/** 选分类：自绘按钮列表没有原生 change 事件，选完主动清掉错误 */
function selectType(value) {
  form.type = value
  formRef.value?.clearValidate('type')
}

/** 截图上传：COMPLAINT 同档（仅图片 ≤10MB），但用 FEEDBACK 这个独立 bizType */
function onImageChange(e) {
  const file = e.target.files && e.target.files[0]
  if (!file) return
  if (form.images.length >= MAX_IMAGES) {
    ElMessage.warning(`截图最多 ${MAX_IMAGES} 张`)
    e.target.value = ''
    return
  }
  if (file.size > MAX_IMAGE_BYTES) {
    ElMessage.warning('单张截图超过 10MB，无法上传')
    e.target.value = ''
    return
  }
  uploading.value = true
  uploadFile(file, 'FEEDBACK')
    .then((res) => {
      if (res?.url) {
        form.images.push(res.url)
        ElMessage.success('截图已上传')
      }
    })
    .catch(() => {
      // 拦截器已弹错（含 403 / magic bytes 判定失败）
    })
    .finally(() => {
      uploading.value = false
      e.target.value = ''
    })
}

function removeImage(i) {
  form.images.splice(i, 1)
}

/* ==================== 我的反馈 ==================== */

const list = ref([])
const listLoading = ref(false)
const listError = ref(false)
const total = ref(0)

/**
 * 分页（P1-9 同款）。
 *
 * <p>原先固定 `{ page: 1, size: 20 }` 且页面没有任何翻页控件 ——
 * 第 21 条以后的反馈用户永远看不到，而「我的反馈」听起来是全部。
 * 现在按后端分页口径（page / size，size 上限 100）取 10 条一页，配 el-pagination。</p>
 */
const page = ref(1)
const size = ref(10)

async function loadList() {
  listLoading.value = true
  try {
    const data = await listMyFeedback({ page: page.value, size: size.value })
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

/** 翻页：重新向后端要对应页的数据（不在前端切数组） */
function onPageChange(p) {
  page.value = p
  loadList()
}

async function submit() {
  // ⚠️ in-flight 锁必须在任何 await 之前同步置位（含下面那次表单校验）
  if (submitting.value) return
  submitting.value = true
  try {
    formRef.value?.clearValidate()
    const valid = await formRef.value?.validate().catch(() => false)
    if (!valid) return
    await createFeedback({
      type: form.type,
      content: form.content.trim(),
      images: form.images.length ? [...form.images] : undefined
    })
    ElMessage.success('反馈已提交，感谢您的建议')
    form.type = ''
    form.content = ''
    form.images = []
    // 新反馈排在最前（create_time DESC），停在旧页码上会看不到刚提交的那条
    page.value = 1
    await loadList()
  } catch (err) {
    // 6003（敏感词）定位到正文字段：撤掉拦截器的顶部 toast，避免同一错误弹两次，
    // 其余错误（网络 / 403）保留全局 toast
    if (err?.code === 6003) {
      ElMessage.closeAll()
      formRef.value?.setFieldError('content', err.message || '内容包含敏感词，请修改后重试')
    }
  } finally {
    submitting.value = false
  }
}

const isEmpty = computed(() => !listLoading.value && !listError.value && list.value.length === 0)

onMounted(loadList)
</script>

<template>
  <NlPageShell title="产品反馈">
    <NlNoticeBar tone="primary">
      这里提的是<strong>产品本身</strong>的建议（界面、流程、功能缺失）。
      若涉及某笔陪诊订单的服务纠纷，请到该订单详情页发起投诉。
    </NlNoticeBar>

    <!-- ==================== 提交 ==================== -->
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent>
      <NlCard title="反馈分类">
        <el-form-item prop="type">
          <ul role="list" class="types">
            <li
              v-for="t in FEEDBACK_TYPES"
              :key="t.value"
              role="button"
              tabindex="0"
              :class="['types__item', { 'is-active': form.type === t.value }]"
              @keydown.enter.prevent="selectType(t.value)"
              @keydown.space.prevent="selectType(t.value)"
              @click="selectType(t.value)"
            >
              {{ t.label }}
            </li>
          </ul>
        </el-form-item>
      </NlCard>

      <NlCard title="想改进什么">
        <el-form-item prop="content">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="6"
            placeholder="例如：订单列表的字有点小，老人模式下也还是偏小，希望能再大一号（10–1000 字）"
            maxlength="1000"
            show-word-limit
          />
        </el-form-item>
      </NlCard>
    </el-form>

    <NlCard title="截图（选填）">
      <p class="nl-caption nl-text-muted">支持图片，单张 ≤ 10MB，最多 {{ MAX_IMAGES }} 张</p>
      <ul v-if="form.images.length" class="shots">
        <li v-for="(u, i) in form.images" :key="u" class="shots__item">
          <img :src="u" alt="反馈截图" class="shots__img" />
          <el-button text size="small" type="danger" @click="removeImage(i)">删除</el-button>
        </li>
      </ul>
      <label
        class="shot-upload"
        :class="{ 'is-disabled': form.images.length >= MAX_IMAGES || uploading }"
      >
        <input
          type="file"
          accept="image/jpeg,image/png,image/webp"
          :disabled="form.images.length >= MAX_IMAGES || uploading"
          @change="onImageChange"
        />
        <span v-if="uploading">上传中…</span>
        <span v-else-if="form.images.length >= MAX_IMAGES">已达上限（{{ MAX_IMAGES }} 张）</span>
        <span v-else>+ 添加截图（{{ form.images.length }}/{{ MAX_IMAGES }}）</span>
      </label>
    </NlCard>

    <div class="submit-bar">
      <el-button
        type="primary"
        size="large"
        round
        class="submit-bar__btn"
        :loading="submitting"
        @click="submit"
      >
        提交反馈
      </el-button>
    </div>

    <!-- ==================== 我的反馈 ==================== -->
    <NlCard title="我的反馈" plain>
      <NlSkeleton v-if="listLoading" :count="2" />

      <NlEmpty
        v-else-if="listError"
        type="network"
        title="反馈记录加载失败"
        action-text="重试"
        @action="loadList"
      />

      <NlEmpty
        v-else-if="isEmpty"
        type="empty"
        title="还没有提交过反馈"
        description="上面填写内容后提交，就能在这里看到处理进度"
      />

      <ul v-else class="hist">
        <li v-for="h in list" :key="h.id" class="hist__item">
          <div class="hist__head">
            <span class="nl-h3">{{ h.typeLabel || h.type }}</span>
            <NlStatusChip scope="feedback" :status="h.status" :text="h.statusLabel" />
          </div>
          <p class="nl-caption hist__content">{{ h.content }}</p>
          <ul v-if="h.images && h.images.length" class="hist__shots">
            <li v-for="u in h.images" :key="u">
              <img :src="u" alt="反馈截图" class="hist__shot" />
            </li>
          </ul>
          <!--
            处理说明只在终态由后端下发（未终态时字段被 Jackson non_null 省略）。
            这里不要写「暂无结论」之类的推测文案，留白即「还在处理」。
          -->
          <p v-if="h.handleResult" class="nl-caption nl-text-2 hist__result">
            处理结果：{{ h.handleResult }}
          </p>
          <span class="nl-caption nl-text-weak is-num">
            #{{ h.id }}<template v-if="h.createTime"> · {{ formatDateTime(h.createTime) }}</template>
          </span>
        </li>
      </ul>

      <!-- 翻页控件：没有它，第 21 条以后的反馈不可达（P1-9 同款） -->
      <el-pagination
        v-if="!listLoading && !listError && total > size"
        class="hist-pager"
        layout="prev, pager, next"
        :total="total"
        :page-size="size"
        :current-page="page"
        @current-change="onPageChange"
      />
    </NlCard>
  </NlPageShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.types {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 0;
  margin: 0;
  list-style: none;

  &__item {
    padding: 6px 14px;
    font-size: var(--nl-font-caption);
    color: var(--nl-text-2);
    background: var(--nl-bg-card);
    border: 1.5px solid var(--nl-border);
    border-radius: 999px;
    cursor: pointer;
    // 触控区下限走 --nl-touch-min（普通形态 48px ≥ 44px 底线，老人形态也是 48px）。
    // 写死 44px 会让老人模式差 4px —— frontend-conventions §3.5 的 ≥48px 是老人模式的硬约束。
    min-height: var(--nl-touch-min);
    display: inline-flex;
    align-items: center;

    &.is-active {
      color: var(--nl-primary-text);
      background: var(--nl-primary-light);
      border-color: var(--nl-primary);
    }
  }
}

.submit-bar {
  padding: var(--nl-space-5) var(--nl-gutter);

  &__btn {
    width: 100%;
  }
}

.shots {
  display: flex;
  flex-wrap: wrap;
  gap: var(--nl-space-3);
  padding: 0;
  margin: 0 0 var(--nl-space-3);
  list-style: none;

  &__item {
    display: flex;
    flex-direction: column;
    gap: 4px;
    align-items: center;
  }

  &__img {
    width: 88px;
    height: 88px;
    object-fit: cover;
    border-radius: $nl-radius-input;
    border: 1px solid var(--nl-border);
  }
}

.shot-upload {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  // 同上：触控区下限走 --nl-touch-min，不写死 44px
  min-height: var(--nl-touch-min);
  padding: 0 var(--nl-space-4);
  font-size: var(--nl-font-caption);
  color: var(--nl-primary-text);
  cursor: pointer;
  background: var(--nl-primary-light);
  border: 1.5px dashed var(--nl-primary);
  border-radius: $nl-radius-input;

  input {
    display: none;
  }

  &.is-disabled {
    color: var(--nl-text-weak);
    cursor: not-allowed;
    background: var(--nl-bg-sunken);
    border-color: var(--nl-border);
  }
}

.hist-pager {
  justify-content: center;
  margin-top: var(--nl-space-4);
}

.hist {
  padding: 0;
  margin: 0;
  list-style: none;

  &__item {
    padding: var(--nl-space-3) 0;
    border-bottom: 1px solid var(--nl-border);

    &:last-child {
      border-bottom: none;
    }
  }

  &__head {
    display: flex;
    gap: var(--nl-space-2);
    align-items: center;
    justify-content: space-between;
  }

  &__content {
    margin: 6px 0 0;
    white-space: pre-wrap;
  }

  &__shots {
    display: flex;
    gap: 6px;
    padding: 0;
    margin: 8px 0 0;
    list-style: none;
  }

  &__shot {
    width: 56px;
    height: 56px;
    object-fit: cover;
    border-radius: $nl-radius-input;
    border: 1px solid var(--nl-border);
  }

  &__result {
    margin: 6px 0 0;
    padding: 8px 10px;
    background: var(--nl-bg-sunken);
    border-radius: $nl-radius-input;
  }
}
</style>
