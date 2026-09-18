<script setup>
/**
 * 陪诊员入驻 · 资质提交（M-20 · design.md §1.2 P1）
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
import { NlPhoneShell, NlMobileOnlyPage, NlCard, NlStatusChip, NlNoticeBar } from '@/components'
import { getMyCompanionApplication, applyCompanion } from '@/api/user'
import { uploadFile } from '@/api/file'

const router = useRouter()

/** 已提交的申请状态：null=从未提交 / PENDING / APPROVED / REJECTED */
const app = ref(null)
const loading = ref(true)

const form = ref({
  realName: '',
  idCard: '',
  serviceArea: '',
  periods: [],
  certificates: [{ name: '健康证', url: '' }],
  remark: ''
})

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
  } catch {
    // 拦截器已弹错
    app.value = null
  } finally {
    loading.value = false
  }
}

function addCert() {
  form.value.certificates.push({ name: '', url: '' })
}
function removeCert(i) {
  form.value.certificates.splice(i, 1)
}

/**
 * 证件材料真实上传：COMPANION_CERT。
 * 前端预校验大小（≤10MB，design.md §2.6）；超限直接提示，不发起请求。
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
  const f = form.value
  if (!f.realName) {
    ElMessage.warning('请填写真实姓名')
    return
  }
  if (!/^\d{17}[\dXx]$/.test(f.idCard)) {
    ElMessage.warning('请输入 18 位身份证号')
    return
  }
  if (!f.serviceArea) {
    ElMessage.warning('请选择服务区域')
    return
  }
  if (!f.periods.length) {
    ElMessage.warning('请至少选择 1 个接单时段')
    return
  }
  const certs = f.certificates
    .filter((c) => c.name && c.url)
    .map((c) => ({ name: c.name.trim(), url: c.url.trim() }))
  if (!certs.length) {
    ElMessage.warning('请至少填写 1 项证件（名称 + 链接）')
    return
  }

  try {
    await ElMessageBox.confirm(
      '提交后将进入审核（一般 1-3 个工作日）。审核通过前不可接单。',
      '确认提交资质',
      { confirmButtonText: '提交审核' }
    )
  } catch {
    return
  }

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

  try {
    await applyCompanion(payload)
    ElMessage.success('已提交，请耐心等待审核')
    await loadApplication()
  } catch {
    // 拦截器已弹错（校验失败 / 已有待审申请等）
  }
}

/** 驳回后重新提交：退回表单 */
function reApply() {
  app.value = null
}

onMounted(loadApplication)
</script>

<template>
  <!-- mobile-only 路由（ADR-0008）：宽屏下由 NlMobileOnlyPage 换成 NlMobileOnlyNotice -->
  <NlMobileOnlyPage>
  <NlPhoneShell :nav="{ title: '陪诊员入驻' }">
    <NlSkeleton v-if="loading" :count="3" class="pad" />

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
        <el-button type="primary" size="large" round class="submit-bar__btn" @click="reApply">
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
      <!-- 实名信息 -->
      <NlCard title="实名信息">
        <el-form size="large" label-position="top">
          <el-form-item label="真实姓名" required>
            <el-input v-model="form.realName" placeholder="请输入身份证上的姓名" maxlength="20" />
          </el-form-item>
          <el-form-item label="身份证号" required>
            <el-input v-model="form.idCard" placeholder="18 位身份证号" maxlength="18" />
          </el-form-item>
        </el-form>
      </NlCard>

      <!-- 接单偏好 -->
      <NlCard title="接单偏好">
        <p class="nl-caption nl-text-muted">选择常驻区域与可接单时段（可多选）</p>
        <div class="pref-area">
          <span class="pref-label">区域</span>
          <div class="chips">
            <button
              v-for="a in areas"
              :key="a"
              :class="['chips__item', { 'is-active': form.serviceArea === a }]"
              @click="form.serviceArea = a"
            >
              {{ a }}
            </button>
          </div>
        </div>
        <div class="pref-area">
          <span class="pref-label">时段</span>
          <el-checkbox-group v-model="form.periods" class="periods">
            <el-checkbox v-for="p in periodOptions" :key="p.key" :label="p.key">
              {{ p.label }}
            </el-checkbox>
          </el-checkbox-group>
        </div>
      </NlCard>

      <!-- 证件（真实上传：COMPANION_CERT） -->
      <NlCard title="资质证件" plain>
        <p class="nl-caption nl-text-weak cert-tip">
          支持 jpg / png / webp / pdf，单文件 ≤ 10MB。
        </p>
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

      <div class="submit-bar">
        <el-button type="primary" size="large" round class="submit-bar__btn" @click="submit">
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
  font-size: 13px;
  color: var(--nl-primary);
  background: var(--nl-bg-card);
  border: 1.5px solid var(--nl-primary);
  border-radius: 10px;
  cursor: pointer;
  white-space: nowrap;

  input {
    display: none;
  }

  &.is-loading {
    color: var(--nl-text-3);
    border-color: var(--nl-border);
    cursor: default;
  }
}

.cert-url {
  margin: 6px 0 0;
  word-break: break-all;
}

.pref-area {
  margin-top: var(--nl-space-3);

  &:first-of-type {
    margin-top: 0;
  }
}

.pref-label {
  display: block;
  margin-bottom: var(--nl-space-2);
  font-size: var(--nl-font-caption);
  color: var(--nl-text-3);
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;

  &__item {
    padding: 6px 14px;
    font-size: 13px;
    color: var(--nl-text-2);
    background: var(--nl-bg-card);
    border: 1.5px solid var(--nl-border);
    border-radius: 999px;
    cursor: pointer;

    &.is-active {
      color: var(--nl-primary);
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
