<script setup>
/**
 * 注册（M-02 · design.md §1.2 P1）
 *
 * - 角色二选一卡片（家属 / 陪诊员）
 *   - 陪诊员注册后引导去资质提交（M-20）
 * - 表单：手机号 + 短信验证码 + 密码 + 确认密码
 * - 用户协议 checkbox
 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { NlPhoneShell } from '@/components'

const router = useRouter()

const role = ref('FAMILY')

const form = ref({
  phone: '',
  smsCode: '',
  password: '',
  password2: '',
  agreed: false
})

function next() {
  if (!/^1\d{10}$/.test(form.value.phone)) {
    ElMessage.warning('请输入正确的手机号')
    return
  }
  if (form.value.smsCode.length !== 4) {
    ElMessage.warning('请输入 4 位短信验证码')
    return
  }
  if (form.value.password.length < 6) {
    ElMessage.warning('密码至少 6 位')
    return
  }
  if (form.value.password !== form.value.password2) {
    ElMessage.warning('两次密码不一致')
    return
  }
  if (!form.value.agreed) {
    ElMessage.warning('请先阅读并同意《用户服务协议》')
    return
  }
  if (role.value === 'COMPANION') {
    ElMessage.success('注册成功，接下来请提交陪诊员资质')
    setTimeout(() => router.push('/companion/entry'), 600)
  } else {
    ElMessage.success('注册成功，请登录')
    setTimeout(() => router.push('/login'), 600)
  }
}
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
        <label class="nl-caption form__label">手机号</label>
        <el-input v-model="form.phone" placeholder="11 位手机号" maxlength="11" size="large" />
      </div>
      <div class="form__group">
        <label class="nl-caption form__label">短信验证码</label>
        <div class="sms">
          <el-input v-model="form.smsCode" placeholder="4 位数字" maxlength="4" size="large" />
          <el-button size="large">获取验证码</el-button>
        </div>
      </div>
      <div class="form__group">
        <label class="nl-caption form__label">密码</label>
        <el-input
          v-model="form.password"
          type="password"
          placeholder="6-32 位，建议大小写 + 数字"
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

      <el-checkbox v-model="form.agreed" class="form__agreed">
        我已阅读并同意 <el-link type="primary" :underline="false">《用户服务协议》</el-link>
        与 <el-link type="primary" :underline="false">《隐私政策》</el-link>
      </el-checkbox>

      <el-button type="primary" size="large" round class="form__submit" @click="next">
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
}
</style>
