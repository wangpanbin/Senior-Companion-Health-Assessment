<script setup>
/**
 * 注册（M-02 · docs/design/design.md §1.2 P1）
 *
 * - 角色二选一卡片（家属 / 陪诊员）—— 按 docs/design/design.md 保持两张卡
 *   - 陪诊员注册后引导去资质提交（M-20）
 * - 表单字段严格对齐后端 `RegisterDTO`（见 docs/api/01-auth-user.md §2）：
 *     username（4-20 位字母数字下划线）/ phone / password / nickname / role
 *     + captchaKey & captchaCode（**图形验证码**）
 *
 * ⚠️ 与骨架版的差异（这是联调时暴露的真实契约错位）：
 *   1. 后端**没有短信验证码**这一套（无短信服务商），验证码是**图形验证码**，
 *      由 `GET /api/auth/captcha` 下发。原表单的「获取验证码 / smsCode」是无效字段。
 *   2. 后端 `RegisterDTO` 必填 `username` 与 `nickname`，原表单两个都没有 ——
 *      不补的话提交必然 400。
 *   3. `role` 后端只接受 ELDER / FAMILY / COMPANION，**ADMIN 永远不可能注册出来**
 *      （见 RegisterDTO 类注释，那是权限体系的根）。因此这里只给两选一。
 *   4. 注册成功后后端返回 `{ userId }`，**不返回令牌**，必须回登录页重新登录。
 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { NlPageShell } from '@/components'
import { getCaptcha, register } from '@/api/auth'
import { PHONE_RE, PHONE_MSG, PASSWORD_RE, PASSWORD_MSG } from '@/constants/validation'

const router = useRouter()

const role = ref('FAMILY')
/**
 * 提交中的 in-flight 锁（必须在任何 await 之前同步置位）。
 */
const submitting = ref(false)
const captchaImage = ref('')

const formRef = ref()
const form = ref({
  username: '',
  nickname: '',
  phone: '',
  password: '',
  password2: '',
  captchaKey: '',
  captchaCode: '',
  agreed: false
})

/** 两次密码是否一致（确认密码是纯前端字段，后端没有它） */
function validatePassword2(rule, value, callback) {
  if (!value) {
    callback(new Error('请再次输入密码'))
    return
  }
  if (value !== form.value.password) {
    callback(new Error('两次密码不一致'))
    return
  }
  callback()
}

/** 是否已勾选协议（勾选框的文案在模板里，规则只管「必须勾」） */
function validateAgreed(rule, value, callback) {
  if (!value) {
    callback(new Error('请先阅读并同意《用户服务协议》'))
    return
  }
  callback()
}

/**
 * 表单规则。
 *
 * <p>⚠️ 每条规则的含义与文案都与后端 {@code RegisterDTO} 的注解一一对应
 * （见 {@code docs/api/01-auth-user.md} §2），前端不再另写一套预校验。</p>
 *
 * <p>此前这里是「一串 if + ElMessage.warning + return」：一次只报一个错，
 * 改掉一个错要再点一次提交才能看到下一个错，而且提示不落在字段上。</p>
 */
const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    // 后端 RegisterDTO#username： ^[A-Za-z0-9_]{4,20}$
    { pattern: /^[A-Za-z0-9_]{4,20}$/, message: '用户名为 4-20 位字母、数字或下划线', trigger: 'blur' }
  ],
  nickname: [
    { required: true, message: '请输入昵称', trigger: 'blur' },
    { min: 2, max: 20, message: '昵称为 2-20 个字符', trigger: 'blur' }
  ],
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: PHONE_RE, message: PHONE_MSG, trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { pattern: PASSWORD_RE, message: PASSWORD_MSG, trigger: 'blur' }
  ],
  password2: [{ validator: validatePassword2, trigger: 'blur' }],
  captchaCode: [{ required: true, message: '请输入图形验证码', trigger: 'blur' }],
  agreed: [{ validator: validateAgreed, trigger: 'change' }]
}

async function loadCaptcha() {
  try {
    const data = await getCaptcha()
    captchaImage.value = data?.captchaImage || ''
    form.value.captchaKey = data?.captchaKey || ''
  } catch {
    // 提示已由 axios 拦截器统一弹出
    captchaImage.value = ''
    form.value.captchaKey = ''
  }
}

async function next() {
  // ⚠️ in-flight 锁必须在任何 await 之前同步置位
  if (submitting.value) return
  submitting.value = true
  try {
    // 一次性校验全部字段：错误落在各自字段下方，不用一轮轮试
    const valid = await formRef.value?.validate().catch(() => false)
    if (!valid) return
    const f = form.value
    await register({
      username: f.username,
      nickname: f.nickname,
      phone: f.phone,
      password: f.password,
      role: role.value,
      captchaKey: f.captchaKey,
      captchaCode: f.captchaCode
    })

    if (role.value === 'COMPANION') {
      ElMessage.success('注册成功，请登录后提交陪诊员资质')
    } else {
      ElMessage.success('注册成功，请登录')
    }
    // 注册只回 userId，没有令牌可存，必须走一次真实登录
    setTimeout(() => router.push('/login'), 600)
  } catch {
    // 失败后立刻换一张验证码：后端 `CaptchaServiceImpl#validate` 用的是
    // getAndDelete，无论校验成功还是失败，这个 captchaKey 都已作废。
    // 不刷新的话用户重试必然拿到「验证码错误」，会以为是自己眼花了。
    form.value.captchaCode = ''
    await loadCaptcha()
  } finally {
    submitting.value = false
  }
}

loadCaptcha()
</script>

<template>
  <NlPageShell title="注册账号">
    <!-- 角色选择 -->
    <section class="role-pick">
      <button
        :class="['role-pick__card', { 'is-active': role === 'FAMILY' }]"
        @click="role = 'FAMILY'"
      >
        <svg viewBox="0 0 32 32" width="36" height="36" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="16" cy="12" r="5" />
          <path d="M6 27c0-6 4-9 10-9s10 3 10 9" />
        </svg>
        <div class="role-pick__label">我是家属</div>
        <div class="role-pick__hint">帮父母下单 · 管用药 · 看进度</div>
      </button>
      <button
        :class="['role-pick__card', { 'is-active': role === 'COMPANION' }]"
        @click="role = 'COMPANION'"
      >
        <svg viewBox="0 0 32 32" width="36" height="36" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="16" cy="12" r="5" />
          <path d="M6 27c0-6 4-9 10-9s10 3 10 9" />
          <path d="M22 8l3-3M22 12h6" />
        </svg>
        <div class="role-pick__label">我是陪诊员</div>
        <div class="role-pick__hint">接单 · 服务 · 结算</div>
      </button>
    </section>

    <!-- 表单 -->
    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-position="top"
      class="form"
      @submit.prevent="next"
    >
      <el-form-item label="用户名" prop="username">
        <el-input v-model="form.username" placeholder="4-20 位字母、数字或下划线" maxlength="20" size="large" />
      </el-form-item>
      <el-form-item label="昵称" prop="nickname">
        <el-input v-model="form.nickname" placeholder="2-20 个字符，如「张大爷」" maxlength="20" size="large" />
      </el-form-item>
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
      <el-form-item label="密码" prop="password">
        <el-input
          v-model="form.password"
          type="password"
          placeholder="6-32 位，需同时包含字母与数字"
          show-password
          size="large"
        />
      </el-form-item>
      <el-form-item label="确认密码" prop="password2">
        <el-input
          v-model="form.password2"
          type="password"
          placeholder="再次输入"
          show-password
          size="large"
        />
      </el-form-item>
      <el-form-item label="图形验证码" prop="captchaCode">
        <div class="sms">
          <el-input v-model="form.captchaCode" placeholder="右侧 4 位" maxlength="4" size="large" />
          <div role="button" tabindex="0" class="sms__img" title="点击更换验证码" @keydown.enter.prevent="loadCaptcha" @keydown.space.prevent="loadCaptcha" @click="loadCaptcha">
            <img v-if="captchaImage" :src="captchaImage" alt="验证码" />
            <span v-else class="nl-caption nl-text-weak">点击刷新</span>
          </div>
        </div>
      </el-form-item>

      <el-form-item prop="agreed">
        <el-checkbox v-model="form.agreed" class="form__agreed">
          我已阅读并同意 <el-link type="primary" underline="never">《用户服务协议》</el-link>
          与 <el-link type="primary" underline="never">《隐私政策》</el-link>
        </el-checkbox>
      </el-form-item>

      <el-button
        type="primary"
        size="large"
        round
        class="form__submit"
        :loading="submitting"
        @click="next"
      >
        {{ role === 'COMPANION' ? '注册并提交资质' : '注册' }}
      </el-button>

      <p class="form__login">
        已有账号？
        <el-link type="primary" underline="never" @click="router.push('/login')">立即登录</el-link>
      </p>
    </el-form>
  </NlPageShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.role-pick {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--nl-space-3);
  padding: 0 var(--nl-gutter);

  &__card {
    display: flex;
    flex-direction: column;
    gap: 4px;
    align-items: center;
    justify-content: center;
    padding: var(--nl-space-4);
    background: var(--nl-bg-card);
    border: 1.5px solid var(--nl-border);
    border-radius: var(--nl-radius-card);
    cursor: pointer;
    color: var(--nl-text-2);

    &.is-active {
      color: var(--nl-primary-text);
      background: var(--nl-primary-light);
      border-color: var(--nl-primary);
    }
  }

  &__label {
    margin-top: var(--nl-space-2);
    font-size: var(--nl-font-h3);
    font-weight: 600;
  }

  &__hint {
    font-size: var(--nl-font-caption);
    color: var(--nl-text-2);
    text-align: center;
  }
}

.form {
  padding: 0 var(--nl-gutter);
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-3);

  /* el-form-item 的间距与标签样式：保持与迁移前「label 在上」的观感一致 */
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

  &__agreed {
    margin: var(--nl-space-2) 0;
  }

  &__submit {
    width: 100%;
    margin-top: var(--nl-space-3);
    font-size: var(--nl-font-h3);
    font-weight: 500;
  }

  &__login {
    margin-top: var(--nl-space-3);
    font-size: var(--nl-font-caption);
    color: var(--nl-text-2);
    text-align: center;
  }
}

.sms {
  display: flex;
  gap: var(--nl-space-2);

  :deep(.el-input) {
    flex: 1;
  }

  &__img {
    display: flex;
    flex-shrink: 0;
    align-items: center;
    justify-content: center;
    width: 120px;
    height: 40px;
    overflow: hidden;
    cursor: pointer;
    background: var(--nl-bg-sunken);
    border: 1px solid var(--nl-border);
    border-radius: var(--nl-radius-input);

    img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }
  }
}
</style>
