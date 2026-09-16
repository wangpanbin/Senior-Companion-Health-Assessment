<script setup>
/**
 * 找回密码（M-03 · design.md §1.2 P2）
 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { NlPhoneShell } from '@/components'

const router = useRouter()

const form = ref({
  phone: '',
  smsCode: '',
  password: '',
  password2: ''
})

function submit() {
  if (!/^1\d{10}$/.test(form.value.phone)) {
    ElMessage.warning('请输入正确的手机号')
    return
  }
  if (form.value.smsCode.length !== 4) {
    ElMessage.warning('请输入 4 位短信验证码')
    return
  }
  if (form.value.password.length < 6) {
    ElMessage.warning('新密码至少 6 位')
    return
  }
  if (form.value.password !== form.value.password2) {
    ElMessage.warning('两次密码不一致')
    return
  }
  ElMessage.success('密码已重置，请用新密码登录')
  setTimeout(() => router.push('/login'), 800)
}
</script>

<template>
  <NlPhoneShell :nav="{ title: '找回密码' }">
    <p class="nl-caption nl-text-muted hint">
      通过手机号验证后可重置密码，重置后会自动清除登录态。
    </p>

    <div class="form">
      <div class="form__group">
        <label class="nl-caption form__label">注册手机号</label>
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
        <label class="nl-caption form__label">新密码</label>
        <el-input
          v-model="form.password"
          type="password"
          placeholder="6-32 位"
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

      <el-button type="primary" size="large" round class="form__submit" @click="submit">
        重置密码
      </el-button>

      <p class="form__login">
        想起来了？
        <el-link type="primary" :underline="false" @click="router.push('/login')">返回登录</el-link>
      </p>
    </div>
  </NlPhoneShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.hint {
  padding: 0 var(--nl-gutter);
  margin: 0 0 var(--nl-space-3);
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

  &__submit {
    width: 100%;
    margin-top: var(--nl-space-3);
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
