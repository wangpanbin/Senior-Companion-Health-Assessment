<script setup>
/**
 * 注册（M-02 · design.md §1.2 P1）
 *
 * - 角色二选一卡片（家属 / 陪诊员）—— 按 design.md 保持两张卡
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
import { NlPhoneShell } from '@/components'
import { getCaptcha, register } from '@/api/auth'

const router = useRouter()

const role = ref('FAMILY')
const loading = ref(false)
const captchaImage = ref('')

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
  const f = form.value

  /* ---------- 前端预校验：与后端注解保持同一套规则，避免无谓的 400 ---------- */
  if (!/^[A-Za-z0-9_]{4,20}$/.test(f.username)) {
    ElMessage.warning('用户名为 4-20 位字母、数字或下划线')
    return
  }
  if (f.nickname.length < 2 || f.nickname.length > 20) {
    ElMessage.warning('昵称为 2-20 个字符')
    return
  }
  if (!/^1[3-9]\d{9}$/.test(f.phone)) {
    ElMessage.warning('请输入正确的 11 位手机号')
    return
  }
  // 后端 regexp 是 ^(?=.*[A-Za-z])(?=.*\d)\S{6,32}$ —— 必须同时含字母与数字
  if (!/^(?=.*[A-Za-z])(?=.*\d)\S{6,32}$/.test(f.password)) {
    ElMessage.warning('密码为 6-32 位，且需同时包含字母与数字')
    return
  }
  if (f.password !== f.password2) {
    ElMessage.warning('两次密码不一致')
    return
  }
  if (!f.captchaCode) {
    ElMessage.warning('请输入图形验证码')
    return
  }
  if (!f.agreed) {
    ElMessage.warning('请先阅读并同意《用户服务协议》')
    return
  }

  loading.value = true
  try {
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
    f.captchaCode = ''
    await loadCaptcha()
  } finally {
    loading.value = false
  }
}

loadCaptcha()
</script>

<template>
  <NlPhoneShell :nav="{ title: '注册账号' }">
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
    <div class="form">
      <div class="form__group">
        <label class="nl-caption form__label">用户名</label>
        <el-input v-model="form.username" placeholder="4-20 位字母、数字或下划线" maxlength="20" size="large" />
      </div>
      <div class="form__group">
        <label class="nl-caption form__label">昵称</label>
        <el-input v-model="form.nickname" placeholder="2-20 个字符，如「张大爷」" maxlength="20" size="large" />
      </div>
      <div class="form__group">
        <label class="nl-caption form__label">手机号</label>
        <el-input v-model="form.phone" placeholder="11 位手机号" maxlength="11" size="large" />
      </div>
      <div class="form__group">
        <label class="nl-caption form__label">密码</label>
        <el-input
          v-model="form.password"
          type="password"
          placeholder="6-32 位，需同时含字母与数字"
          show-password
          size="large"
        />
      </div>
      <div class="form__group">
        <label class="nl-caption form__label">确认密码</label>
        <el-input
          v-model="form.password2"
          type="password"
          placeholder="再次输入"
          show-password
          size="large"
        />
      </div>
      <div class="form__group">
        <label class="nl-caption form__label">图形验证码</label>
        <div class="sms">
          <el-input v-model="form.captchaCode" placeholder="右侧 4 位" maxlength="4" size="large" />
          <div class="sms__img" title="点击更换验证码" @click="loadCaptcha">
            <img v-if="captchaImage" :src="captchaImage" alt="验证码" />
            <span v-else class="nl-caption nl-text-weak">点击刷新</span>
          </div>
        </div>
      </div>

      <el-checkbox v-model="form.agreed" class="form__agreed">
        我已阅读并同意 <el-link type="primary" :underline="false">《用户服务协议》</el-link>
        与 <el-link type="primary" :underline="false">《隐私政策》</el-link>
      </el-checkbox>

      <el-button
        type="primary"
        size="large"
        round
        class="form__submit"
        :loading="loading"
        @click="next"
      >
        {{ role === 'COMPANION' ? '注册并提交资质' : '注册' }}
      </el-button>

      <p class="form__login">
        已有账号？
        <el-link type="primary" :underline="false" @click="router.push('/login')">立即登录</el-link>
      </p>
    </div>
  </NlPhoneShell>
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
      color: var(--nl-primary);
      background: var(--nl-primary-light);
      border-color: var(--nl-primary);
    }
  }

  &__label {
    margin-top: var(--nl-space-2);
    font-size: 15px;
    font-weight: 600;
  }

  &__hint {
    font-size: 11px;
    color: var(--nl-text-3);
    text-align: center;
  }
}

.form {
  padding: 0 var(--nl-gutter);
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-3);

  &__group {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  &__label {
    color: var(--nl-text-3);
  }

  &__agreed {
    margin: var(--nl-space-2) 0;
  }

  &__submit {
    width: 100%;
    margin-top: var(--nl-space-3);
    font-size: 16px;
    font-weight: 500;
  }

  &__login {
    margin-top: var(--nl-space-3);
    font-size: 13px;
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
