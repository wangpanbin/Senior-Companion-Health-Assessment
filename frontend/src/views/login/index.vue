<script setup>
import { reactive, ref, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/modules/user'
import { getCaptcha } from '@/api/auth'

/**
 * 登录页（M2 已对接真实接口）。
 *
 * ⚠️ 验证码是「一次性」的：服务端校验时就把 Redis 里的那一条删掉了（防重放），
 *    所以**每次提交之后都必须换一张**，否则用户第二次点登录必然收到「验证码错误」，
 *    而他会以为是自己的问题。这是登录页最容易踩的坑。
 *
 * 合规提示：登录失败提示统一为「账号或密码错误」，不区分账号是否存在，避免账号枚举。
 */
const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const formRef = ref()
const loading = ref(false)
const captchaImage = ref('')

const form = reactive({
  username: '',
  password: '',
  captchaKey: '',
  captchaCode: ''
})

const rules = {
  username: [{ required: true, message: '请输入手机号或用户名', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 32, message: '密码长度为 6-32 位', trigger: 'blur' }
  ],
  captchaCode: [{ required: true, message: '请输入验证码', trigger: 'blur' }]
}

const redirect = computed(() => route.query.redirect || '/dashboard')

/** 仅开发环境展示演示账号，避免生产包体里带默认口令 */
const demoAccounts = import.meta.env.DEV
  ? [
      { role: '管理员', account: 'admin' },
      { role: '家属', account: 'fam001' },
      { role: '陪诊员', account: 'comp001' },
      { role: '老年患者', account: 'elder001' }
    ]
  : []

function fillDemo(account) {
  form.username = account
  form.password = 'Nl@123456'
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    await userStore.login({
      username: form.username,
      password: form.password,
      captchaKey: form.captchaKey,
      captchaCode: form.captchaCode
    })
    ElMessage.success('登录成功')
    router.push(redirect.value)
  } catch {
    // 错误提示已在 axios 拦截器统一处理
  } finally {
    loading.value = false
    form.captchaCode = ''
    // 无论成功失败，验证码都已作废，必须换一张新的
    await loadCaptcha()
  }
}

async function loadCaptcha() {
  try {
    const data = await getCaptcha()
    captchaImage.value = data?.captchaImage || ''
    form.captchaKey = data?.captchaKey || ''
  } catch {
    captchaImage.value = ''
    form.captchaKey = ''
  }
}

loadCaptcha()
</script>

<template>
  <div class="login">
    <div class="login__card">
      <div class="login__brand">
        <div class="login__mark">银</div>
        <h1 class="login__title">银龄伴诊</h1>
        <p class="login__subtitle">老年人就医陪诊与用药协同管理平台</p>
      </div>

      <el-alert
        v-if="demoAccounts.length"
        class="login__alert"
        type="info"
        :closable="false"
        show-icon
        title="演示账号（开发环境可见，初始密码 Nl@123456）"
      >
        <div class="login__demo">
          <el-tag
            v-for="item in demoAccounts"
            :key="item.account"
            class="login__demo-tag"
            type="info"
            effect="plain"
            @click="fillDemo(item.account)"
          >
            {{ item.role }} {{ item.account }}
          </el-tag>
        </div>
      </el-alert>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        size="large"
        label-position="top"
        @submit.prevent="handleSubmit"
      >
        <el-form-item label="手机号 / 用户名" prop="username">
          <el-input v-model="form.username" placeholder="请输入手机号或用户名" clearable />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            show-password
            @keyup.enter="handleSubmit"
          />
        </el-form-item>

        <el-form-item label="验证码" prop="captchaCode">
          <div class="login__captcha">
            <el-input
              v-model="form.captchaCode"
              placeholder="请输入验证码"
              @keyup.enter="handleSubmit"
            />
            <div class="login__captcha-img" title="点击更换验证码" @click="loadCaptcha">
              <img v-if="captchaImage" :src="captchaImage" alt="验证码" />
              <span v-else class="login__captcha-empty">点击刷新</span>
            </div>
          </div>
        </el-form-item>

        <el-form-item>
          <el-button
            type="primary"
            size="large"
            class="login__submit"
            :loading="loading"
            @click="handleSubmit"
          >
            登 录
          </el-button>
        </el-form-item>
      </el-form>

      <div class="login__footer nl-muted">
        软件工程课程设计 · 银龄伴诊团队
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.login {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: $space-base;
  background: linear-gradient(140deg, #eef5f1 0%, #f7f4ee 100%);

  &__card {
    width: 100%;
    max-width: 440px;
    padding: $space-xl;
    background: #ffffff;
    border-radius: $radius-lg;
    box-shadow: 0 8px 32px rgb(31 39 51 / 10%);
  }

  &__brand {
    margin-bottom: $space-lg;
    text-align: center;
  }

  &__mark {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 56px;
    height: 56px;
    margin-bottom: $space-sm;
    font-size: 28px;
    font-weight: 700;
    color: #ffffff;
    background: var(--nl-color-primary);
    border-radius: 50%;
  }

  &__title {
    margin: 0;
    font-size: 24px;
    font-weight: 700;
  }

  &__subtitle {
    margin: 6px 0 0;
    font-size: 14px;
    color: var(--nl-text-secondary);
  }

  &__alert {
    margin-bottom: $space-base;
  }

  &__demo {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin-top: 6px;
  }

  &__demo-tag {
    cursor: pointer;
  }

  &__captcha {
    display: flex;
    gap: 10px;
    width: 100%;
  }

  &__captcha-img {
    display: flex;
    flex-shrink: 0;
    align-items: center;
    justify-content: center;
    width: 120px;
    height: 40px;
    overflow: hidden;
    cursor: pointer;
    background: #f4f6f8;
    border: 1px solid var(--nl-border-color);
    border-radius: $radius-sm;

    img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }
  }

  &__captcha-empty {
    font-size: 12px;
    color: var(--nl-text-secondary);
  }

  &__submit {
    width: 100%;
  }

  &__footer {
    margin-top: $space-base;
    font-size: 12px;
    text-align: center;
  }
}
</style>
