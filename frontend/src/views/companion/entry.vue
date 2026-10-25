<script setup>
/**
 * 陪诊员入驻 · 资质提交（M-20 · docs/design/design.md §1.2 P1）
 *
 * 数据来源：
 *   - GET  /user/companion/application → getMyCompanionApplication（我的资质状态）
 *   - POST /user/companion/apply       → applyCompanion（提交资质申请）
 *
 * 提交入参严格对齐后端 CompanionApplyDTO：
 *   realName / idCard / serviceArea(单值) / availableTime(单值) /
 *   certificates(List<{name,url}>) / remark
 *
 * ⚠️ 证件材料走通用上传端点 POST /api/file/upload（bizType=COMPANION_CERT）：
 *    选择文件 → uploadFile → 把返回的 url 写回 certificates[i].url；支持图片 / PDF，单文件 ≤10MB。
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { NlPhoneShell, NlMobileOnlyPage, NlCard, NlStatusChip, NlNoticeBar, NlSkeleton, NlEmpty } from '@/components'
import { getMyCompanionApplication, applyCompanion } from '@/api/user'
import { uploadFile } from '@/api/file'
import { confirmThen } from '@/utils/confirm'
import { ID_CARD_RE, NAME_MIN, NAME_MAX, ID_CARD_MSG, NAME_MSG } from '@/constants/validation'

const router = useRouter()

/** 已提交的申请状态：null=从未提交 / PENDING / APPROVED / REJECTED */
const app = ref(null)
const loading = ref(true)
/** R4：资质状态加载失败（不能静默回落成「未提交过」表单，否则用户会重复提交） */
const loadError = ref(false)

/**
 * 提交 in-flight 锁（P1-5）。
 *
 * ⚠️ 必须在 submit() 的**第一行同步置位**：置在第一个 await 之后，
 *    两次快速点击会一起穿过 validate / confirm，造成重复提交资质申请。
 */
const submitting = ref(false)

const form = ref({
  realName: '',
  idCard: '',
  serviceArea: '',
  periods: [],
  certificates: [{ name: '健康证', url: '' }],
  remark: ''
})

/**
 * 校验规则（P2-1 / P2-4）。
 *
 * 旧写法是「一次性 toast」：`if (!x) { ElMessage.warning('...'); return }` ——
 * 一次只报一个错、错在哪个字段不定位，用户得自己猜该改哪一栏。
 * 现在改成 el-form + rules：一次把错全标出来，错误落在对应字段下方。
 *
 * 规则与文案沿用原有口径（必填 / 18 位身份证 / 区域 / 时段 / 至少 1 项证件），
 * 其中身份证与姓名长度改用 @/constants/validation 的共享口径 ——
 * 旧正则 `^\d{17}[\dXx]$` 比后端松，非法身份证会一路走到后端才被拒，
 * 用户只看到泛化的「请求参数有误」。
 */
const formRef = ref(null)
const rules = {
  realName: [
    { required: true, message: '请填写真实姓名', trigger: 'blur' },
    { min: NAME_MIN, max: NAME_MAX, message: NAME_MSG, trigger: 'blur' }
  ],
  idCard: [
    { required: true, message: '请输入 18 位身份证号', trigger: 'blur' },
    { pattern: ID_CARD_RE, message: ID_CARD_MSG, trigger: 'blur' }
  ],
  serviceArea: [{ required: true, message: '请选择服务区域', trigger: 'change' }],
  periods: [{ required: true, message: '请至少选择 1 个接单时段', trigger: 'change' }],
  certificates: [
    {
      required: true,
      trigger: 'blur',
      validator: (_rule, value, cb) => {
        // 后端要求「至少 1 项**完整**证件」：名称与链接都要有（见 CompanionApplyDTO）
        const list = (value || []).filter((c) => c.name && c.url)
        if (list.length) cb()
        else cb(new Error('请至少填写 1 项证件（名称 + 链接）'))
      }
    }
  ]
}

const areas = ['朝阳区', '海淀区', '丰台区', '西城区', '东城区']
const periodOptions = [
  { key: 'morning', label: '上午（06:00 - 12:00）' },
  { key: 'noon', label: '中午（12:00 - 14:00）' },
  { key: 'afternoon', label: '下午（14:00 - 18:00）' },
  { key: 'evening', label: '晚上（18:00 - 22:00）' }
]

/** 是否已提交过（展示审核状态，隐藏表单） */
const submitted = computed(() => app.value != null)

/** 当前正在上传的证件下标（-1 表示无） */
const uploadingCertIndex = ref(-1)

async function loadApplication() {
  try {
    const data = await getMyCompanionApplication()
    app.value = data ?? null
    loadError.value = false
  } catch {
    // 拦截器已弹错
    app.value = null
    loadError.value = true
  } finally {
    loading.value = false
  }
}

/** 错误态重试：需重新点亮骨架屏，loadApplication 只在 finally 里关 loading */
async function reloadApplication() {
  loading.value = true
  await loadApplication()
}

function addCert() {
  form.value.certificates.push({ name: '', url: '' })
}
function removeCert(i) {
  form.value.certificates.splice(i, 1)
  revalidateCerts()
}

/**
 * 证件是自绘列表（名称 + 上传链接），不是 el-form 控件，
 * change 触发器不会自动跑 —— 上传成功 / 删除后手动校一次，
 * 让「至少 1 项证件」的错误实时跟着内容走。
 */
function revalidateCerts() {
  void formRef.value?.validateField('certificates', () => {})
}

/**
 * 选服务区域：区域是自绘药丸，也不是 el-form 控件，
 * 选完手动校一次，让「请选择服务区域」的错误立刻消失。
 */
function pickArea(a) {
  form.value.serviceArea = a
  void formRef.value?.validateField('serviceArea', () => {})
}

/**
 * 证件材料真实上传：COMPANION_CERT。
 * 前端预校验大小（≤10MB，docs/design/design.md §2.6）；超限直接提示，不发起请求。
 * 失败（含 403 / 参数错误）时 url 不写入、保留原值，仅由拦截器统一弹错。
 */
function onCertFileChange(i, e) {
  const file = e.target.files && e.target.files[0]
  if (!file) return
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.warning('文件超过 10MB，无法上传')
    e.target.value = ''
    return
  }
  uploadingCertIndex.value = i
  const cert = form.value.certificates[i]
  const prevUrl = cert.url
  uploadFile(file, 'COMPANION_CERT')
    .then((res) => {
      if (res?.url) {
        cert.url = res.url // 成功才写入，失败保留原值
        ElMessage.success('证件已上传')
        revalidateCerts()
      }
    })
    .catch(() => {
      cert.url = prevUrl // 失败不写入
    })
    .finally(() => {
      uploadingCertIndex.value = -1
      e.target.value = ''
    })
}

async function submit() {
  // ⚠️ in-flight 锁必须在第一个 await 之前**同步**置位：
  //    置在 await 之后，两次快速点击会一起穿过 validate / confirm，造成重复提交。
  if (submitting.value) return
  if (!formRef.value) return
  submitting.value = true
  try {
    // 字段级校验交给 el-form + rules：错误落在对应字段下方，一次全标出
    const valid = await formRef.value.validate().then(() => true, () => false)
    if (!valid) return

    const f = form.value
    const certs = f.certificates
      .filter((c) => c.name && c.url)
      .map((c) => ({ name: c.name.trim(), url: c.url.trim() }))
    if (!certs.length) {
      ElMessage.warning('请至少填写 1 项证件（名称 + 链接）')
      return
    }

    const ok = await confirmThen(() =>
      ElMessageBox.confirm(
        '提交后将进入审核（一般 1-3 个工作日）。审核通过前不可接单。',
        '确认提交资质',
        { confirmButtonText: '提交审核' }
      )
    )
    if (!ok) return

    const payload = {
      realName: f.realName.trim(),
      idCard: f.idCard.trim(),
      serviceArea: f.serviceArea,
      availableTime: f.periods
        .map((k) => periodOptions.find((p) => p.key === k)?.label || k)
        .join('；'),
      certificates: certs,
      remark: f.remark ? f.remark.trim() : undefined
    }

    await applyCompanion(payload)
    ElMessage.success('已提交，请耐心等待审核')
    await loadApplication()
  } catch {
    // 拦截器已弹错（校验失败 / 已有待审申请等）
  } finally {
    submitting.value = false
  }
}

/** 驳回后重新提交：退回表单 */
function reApply() {
  if (submitting.value) return
  app.value = null
}

onMounted(loadApplication)
</script>

<template>
  <!-- mobile-only 路由（ADR-0008）：宽屏下由 NlMobileOnlyPage 换成 NlMobileOnlyNotice -->
  <NlMobileOnlyPage>
  <NlPhoneShell :nav="{ title: '陪诊员入驻' }">
    <NlSkeleton v-if="loading" :count="3" class="pad" />

    <NlEmpty
      v-else-if="loadError"
      type="network"
      title="资质状态加载失败"
      description="无法确认您是否已提交过资质，请重试"
      action-text="重试"
      @action="reloadApplication"
    />

    <!-- 已提交：展示审核状态 -->
    <template v-else-if="submitted">
      <NlNoticeBar v-if="app.auditStatus === 'PENDING'" tone="success">
        资质审核中，请耐心等待。审核结果将通过站内信通知。
      </NlNoticeBar>
      <NlNoticeBar v-else-if="app.auditStatus === 'APPROVED'" tone="success">
        资质已通过，现在可以开始接单了。
      </NlNoticeBar>
      <NlNoticeBar v-else-if="app.auditStatus === 'REJECTED'" tone="danger">
        资质未通过：{{ app.rejectReason || '请联系管理员' }}
      </NlNoticeBar>

      <NlCard plain>
        <div class="status">
          <NlStatusChip scope="audit" :status="app.auditStatus" />
          <span class="nl-caption nl-text-muted">
            {{ app.auditStatus === 'PENDING' ? '预计 1-3 个工作日反馈' : '' }}
            {{ app.auditStatus === 'APPROVED' ? '可前往接单大厅接单' : '' }}
            {{ app.auditStatus === 'REJECTED' ? '可修改后重新提交' : '' }}
          </span>
        </div>
      </NlCard>

      <div v-if="app.auditStatus === 'REJECTED'" class="submit-bar">
        <el-button
          type="primary"
          size="large"
          round
          class="submit-bar__btn"
          :disabled="submitting"
          @click="reApply"
        >
          重新提交资质
        </el-button>
      </div>
      <div v-else-if="app.auditStatus === 'APPROVED'" class="submit-bar">
        <el-button type="primary" size="large" round class="submit-bar__btn" @click="router.push('/companion/hall')">
          去接单大厅
        </el-button>
      </div>
    </template>

    <!-- 未提交：填写表单 -->
    <template v-else>
      <!--
        一个 el-form 罩住三张卡片：字段级错误统一落在对应字段下方（P2-1）。
        .apply-form 补上原来由 NlPhoneShell 的 gap 提供的卡片间距。
      -->
      <el-form
        ref="formRef"
        class="apply-form"
        :model="form"
        :rules="rules"
        size="large"
        label-position="top"
      >
        <!-- 实名信息 -->
        <NlCard title="实名信息">
          <el-form-item label="真实姓名" prop="realName" required>
            <el-input v-model="form.realName" placeholder="请输入身份证上的姓名" maxlength="20" />
          </el-form-item>
          <el-form-item label="身份证号" prop="idCard" required>
            <el-input v-model="form.idCard" placeholder="18 位身份证号" maxlength="18" />
          </el-form-item>
        </NlCard>

        <!-- 接单偏好 -->
        <NlCard title="接单偏好">
          <p class="nl-caption nl-text-muted">选择常驻区域与可接单时段（可多选）</p>
          <el-form-item label="服务区域" prop="serviceArea" required>
            <div class="chips">
              <button
                v-for="a in areas"
                :key="a"
                :class="['chips__item', { 'is-active': form.serviceArea === a }]"
                @click="pickArea(a)"
              >
                {{ a }}
              </button>
            </div>
          </el-form-item>
          <el-form-item label="可接单时段" prop="periods" required>
            <el-checkbox-group v-model="form.periods" class="periods">
              <el-checkbox v-for="p in periodOptions" :key="p.key" :label="p.key">
                {{ p.label }}
              </el-checkbox>
            </el-checkbox-group>
          </el-form-item>
        </NlCard>

        <!-- 证件（真实上传：COMPANION_CERT） -->
        <NlCard title="资质证件" plain>
          <p class="nl-caption nl-text-weak cert-tip">
            支持 jpg / png / webp / pdf，单文件 ≤ 10MB。
          </p>
          <el-form-item label="证件材料" prop="certificates" required>
            <ul class="files">
              <li v-for="(c, i) in form.certificates" :key="i" class="file">
                <div class="file__row">
                  <el-input v-model="c.name" placeholder="证件名称，如：健康证" maxlength="50" />
                  <label class="cert-upload" :class="{ 'is-loading': uploadingCertIndex === i }">
                    <input
                      type="file"
                      accept="image/jpeg,image/png,image/webp,application/pdf"
                      :disabled="uploadingCertIndex === i"
                      @change="onCertFileChange(i, $event)"
                    />
                    <span v-if="uploadingCertIndex === i">上传中…</span>
                    <span v-else-if="c.url">重新上传</span>
                    <span v-else>选择文件</span>
                  </label>
                  <el-button
                    v-if="form.certificates.length > 1"
                    text size="small" type="danger" @click="removeCert(i)"
                  >删除</el-button>
                </div>
                <p v-if="c.url" class="nl-caption nl-text-weak cert-url">{{ c.url }}</p>
              </li>
            </ul>
            <el-button text type="primary" size="small" @click="addCert">+ 添加证件</el-button>
          </el-form-item>
        </NlCard>

        <!-- 补充说明 -->
        <NlCard title="补充说明（选填）">
          <el-input
            v-model="form.remark"
            type="textarea"
            :rows="3"
            maxlength="200"
            placeholder="如：3 年陪诊经验，熟悉省医院各科室"
          />
        </NlCard>
      </el-form>

      <div class="submit-bar">
        <el-button
          type="primary"
          size="large"
          round
          class="submit-bar__btn"
          :loading="submitting"
          @click="submit"
        >
          提交审核
        </el-button>
      </div>
    </template>
  </NlPhoneShell>
  </NlMobileOnlyPage>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.pad {
  padding: 0 var(--nl-gutter);
}

/*
 * el-form 罩住三张卡片后，卡片之间的间距不再由 NlPhoneShell 的 gap 提供
 * （el-form 成了 main 的唯一 flex 子项），这里补回同一档间距。
 */
.apply-form {
  display: flex;
  flex-direction: column;
  gap: var(--nl-gap-section);
}

.status {
  display: flex;
  gap: var(--nl-space-2);
  align-items: center;
}

.cert-tip {
  margin: 0 0 var(--nl-space-2);
}

.files {
  padding: 0;
  margin: 0;
  list-style: none;
}

.file {
  padding: var(--nl-space-2) 0;
  border-bottom: 1px dashed var(--nl-divider);

  &:last-child {
    border-bottom: none;
  }

  &__row {
    display: grid;
    grid-template-columns: 1fr auto auto;
    gap: var(--nl-space-2);
    align-items: center;
  }
}

.cert-upload {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0 14px;
  height: 40px;
  font-size: var(--nl-font-caption);
  color: var(--nl-primary-text);
  background: var(--nl-bg-card);
  border: 1.5px solid var(--nl-primary);
  border-radius: 10px;
  cursor: pointer;
  white-space: nowrap;

  input {
    display: none;
  }

  &.is-loading {
    color: var(--nl-text-2);
    border-color: var(--nl-border);
    cursor: default;
  }
}

.cert-url {
  margin: 6px 0 0;
  word-break: break-all;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;

  &__item {
    padding: 6px 14px;
    font-size: var(--nl-font-caption);
    color: var(--nl-text-2);
    background: var(--nl-bg-card);
    border: 1.5px solid var(--nl-border);
    border-radius: 999px;
    cursor: pointer;

    &.is-active {
      color: var(--nl-primary-text);
      background: var(--nl-primary-light);
      border-color: var(--nl-primary);
    }
  }
}

.periods {
  :deep(.el-checkbox) {
    display: flex;
    margin-right: 0;
    margin-bottom: var(--nl-space-2);
  }
}

.submit-bar {
  padding: var(--nl-space-5) var(--nl-gutter);

  &__btn {
    width: 100%;
  }
}
</style>
