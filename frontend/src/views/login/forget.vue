<script setup>
/**
 * 找回密码（V4 · P1-1 自助找回，去短信化见 ADR-0021）
 *
 * 契约：docs/api/01-auth-user.md §10 – §11
 *
 * - 手机号 → 获取验证码（后端直接回传，**自动填入**下方输入框；60 秒倒计时）
 * - 验证码 + 新密码 → 提交
 * - 成功提示「密码已重置，请重新登录」并跳 /login
 *
 * ⚠️ 不再有短信通道：验证码由 POST /auth/sms-code 在 data 中明文回传，
 * 任何知道手机号的人都能重置密码 —— 这是刻意的取舍（课程演示环境，
 * 无短信服务商预算），仅靠三层频控节流，见 ADR-0021。
 *
 * ⚠️ 错误码（1008 验证码错误 / 过期 / 已用；1009 频控）由 axios 拦截器
 * 直接展示后端文案，本页只做「清空验证码」「启动倒计时」这类状态联动。
 */
import { onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { NlPageShell, NlCard, NlNoticeBar } from '@/components'
import { sendSmsCode, forgotPassword } from '@/api/auth'
import { PHONE_RE, PHONE_MSG, PASSWORD_RE, PASSWORD_MSG } from '@/constants/validation'

const router = useRouter()

const formRef = ref()
const form = ref({
  phone: '',
  smsCode: '',
  newPassword: ''
})

/** 提交中 / 发码中的 in-flight 锁（必须在任何 await 之前同步置位） */
const submitting = ref(false)
const sending = ref(false)

/** 60 秒重发倒计时 */
const countdown = ref(0)
let countdownTimer = null

const SMS_CODE_RE = /^\d{6}$/

const rules = {
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: PHONE_RE, message: PHONE_MSG, trigger: 'blur' }
  ],
  smsCode: [
    { required: true, message: '请先获取验证码', trigger: 'blur' },
    { pattern: SMS_CODE_RE, message: '验证码为 6 位数字', trigger: 'blur' }
  ],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { pattern: PASSWORD_RE, message: PASSWORD_MSG, trigger: 'blur' }
  ]
}

function startCountdown() {
  stopCountdown()
  countdown.value = 60
  countdownTimer = window.setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) stopCountdown()
  }, 1000)
}

function stopCountdown() {
  if (countdownTimer) {
    window.clearInterval(countdownTimer)
    countdownTimer = null
  }
  if (countdown.value < 0) countdown.value = 0
}

onBeforeUnmount(stopCountdown)

/** 只校验手机号，再去要验证码；后端回传后自动填入输入框 */
async function sendCode() {
  if (sending.value || countdown.value > 0) return
  sending.value = true
  try {
    const valid = await formRef.value
      ?.validateField('phone')
      .then(() => true)
      .catch(() => false)
    if (!valid) return

    // 拦截器已拆好 envelope：data 就是 6 位验证码字符串
    const smsCode = await sendSmsCode({ phone: form.value.phone })
    form.value.smsCode = smsCode || ''
    ElMessage.success('验证码已自动填充')
    startCountdown()
  } catch (e) {
    // 1009 频控：即便这次没拿到码，也启动倒计时，避免用户连续点击
    if (e?.code === 1009) startCountdown()
  } finally {
    sending.value = false
  }
}

async function submit() {
  if (submitting.value) return
  submitting.value = true
  try {
    const valid = await formRef.value?.validate().catch(() => false)
    if (!valid) return

    await forgotPassword({
      phone: form.value.phone,
      smsCode: form.value.smsCode,
      newPassword: form.value.newPassword
    })

    ElMessage.success('密码已重置，请重新登录')
    setTimeout(() => router.push('/login'), 600)
  } catch (e) {
    // 1008：验证码错误 / 过期 / 已用 / 错满 5 次已作废 —— 清空输入，引导重新获取
    if (e?.code === 1008) form.value.smsCode = ''
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <NlPageShell title="找回密码">
    <NlNoticeBar tone="info">
      验证码由系统生成并自动填充，仅用于确认本人身份。
    </NlNoticeBar>

    <NlCard title="填写信息">
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        class="form"
        @submit.prevent="submit"
      >
        <el-form-item label="手机号" prop="phone">
          <el-input
            v-model="form.phone"
            type="tel"
            inputmode="numeric"
            placeholder="11 位手机号"
            maxlength="11"
            size="large"
          />
        </el-form-item>

        <el-form-item label="验证码" prop="smsCode">
          <div class="sms">
            <el-input
              v-model="form.smsCode"
              inputmode="numeric"
              placeholder="点击右侧按钮自动填充"
              maxlength="6"
              size="large"
            />
            <el-button
              type="primary"
              plain
              size="large"
              class="sms__btn"
              :loading="sending"
              :disabled="countdown > 0"
              @click="sendCode"
            >
              {{ countdown > 0 ? `${countdown}s 后重发` : '获取验证码' }}
            </el-button>
          </div>
        </el-form-item>

        <el-form-item label="新密码" prop="newPassword">
          <el-input
            v-model="form.newPassword"
            type="password"
            placeholder="6-32 位，需同时包含字母与数字"
            show-password
            size="large"
          />
        </el-form-item>

        <el-button
          type="primary"
          size="large"
          round
          class="form__submit"
          :loading="submitting"
          @click="submit"
        >
          重置密码
        </el-button>

        <p class="form__back">
          <el-link type="primary" underline="never" @click="router.push('/login')">返回登录</el-link>
        </p>
      </el-form>
    </NlCard>

    <NlCard title="还有其他办法吗">
      <p class="nl-caption nl-text-muted fallback">
        验证码没有自动填入、或手机号已停用？也可以联系平台管理员，说明注册时使用的用户名或手机号，
        由管理员在后台核实后协助重置。
      </p>
    </NlCard>
  </NlPageShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.form {
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-3);

  :deep(.el-form-item) {
    margin-bottom: var(--nl-space-3);
  }

  :deep(.el-form-item__label) {
    padding-bottom: 4px;
    color: var(--nl-text-2);
    font-size: var(--nl-font-caption);
    line-height: 1.4;
  }

  :deep(.el-form-item__error) {
    padding-top: 2px;
  }

  &__submit {
    width: 100%;
    margin-top: var(--nl-space-3);
    font-size: var(--nl-font-h3);
    font-weight: 500;
  }

  &__back {
    margin-top: var(--nl-space-3);
    font-size: var(--nl-font-caption);
    color: var(--nl-text-2);
    text-align: center;
  }
}

.sms {
  display: flex;
  gap: var(--nl-space-2);
  width: 100%;

  :deep(.el-input) {
    flex: 1;
    min-width: 0;
  }

  &__btn {
    flex-shrink: 0;
    min-width: 116px;
  }
}

.fallback {
  margin: 0;
  line-height: 1.6;
}
</style>
