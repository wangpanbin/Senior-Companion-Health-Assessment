<script setup>
/**
 * 登录页（M-01 · design.md §1.2）
 *
 * - 顶部品牌区（医疗蓝渐变 + 心形 SVG logo + slogan）
 * - 角色快速选择（4 角色卡片，选中切换对应演示账号）
 * - 表单：手机号 + 密码 + 图形验证码
 * - 底部：忘记密码 + 注册 + 免责文案
 * - 演示账号：仅 DEV 环境可见，避免生产包体里带默认口令
 */
import { reactive, ref, computed, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore, ROLE_LABELS } from '@/store/modules/user'
import { getCaptcha } from '@/api/auth'

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
  captchaCode: '',
  remember: false
})

const rules = {
  username: [{ required: true, message: '请输入手机号或用户名', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 32, message: '密码长度为 6-32 位', trigger: 'blur' }
  ],
  captchaCode: [{ required: true, message: '请输入验证码', trigger: 'blur' }]
}

/**
 * 角色快速选择 —— 仅用于本地演示：选中后把对应种子账号填进表单，
 * 提交仍然走真实的 POST /api/auth/login（含图形验证码），不存在任何绕过登录的通道。
 * 预填只在 DEV 生效，生产构建不会带上种子账号。
 */
const ROLE_CARDS = [
  { role: 'ELDER',     label: '老年患者', hint: '只读视图 · 字号最大', tone: 'success', account: 'elder001' },
  { role: 'FAMILY',    label: '家属',     hint: '下单 · 绑定老人 · 看进度', tone: 'primary', account: 'fam001' },
  { role: 'COMPANION', label: '陪诊员',   hint: '抢单 · 6 节点打卡 · 收入', tone: 'warning', account: 'comp001' },
  { role: 'ADMIN',     label: '管理员',   hint: '数据看板 · 审资质 · 调纠纷', tone: 'purple', account: 'admin' }
]

const activeRole = ref('FAMILY')
const redirect = computed(() => route.query.redirect || '/')

/**
 * 把角色对应的演示账号填进表单（仅 DEV）。
 *
 * 注意：不能只依赖 watch(activeRole) —— 默认值就是 FAMILY，用户点击「家属」
 * 卡片时 activeRole 没有发生变化，watch 不触发，表单保持空白，看起来像点了没反应。
 * 因此 pickRole 里也要显式调用一次。
 */
function applyDemoAccount(role) {
  const card = ROLE_CARDS.find((c) => c.role === role)
  if (import.meta.env.DEV && card) {
    form.username = card.account
    form.password = 'Nl@123456'
  }
}

watch(activeRole, applyDemoAccount)

function pickRole(card) {
  activeRole.value = card.role
  applyDemoAccount(card.role)
  const label = ROLE_LABELS[card.role] || card.role
  ElMessage.info(`已切换到「${label}」演示账号，可在表单中直接登录`)
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

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    await userStore.login({
      username: form.username,
      password: form.password,
      captchaKey: form.captchaKey,
      captchaCode: form.captchaCode,
      role: activeRole.value // 仅前端 demo 透传给后端做角色 hint
    })
    ElMessage.success('登录成功')
    router.push(redirect.value)
  } catch {
    // 错误提示已在 axios 拦截器统一处理
  } finally {
    loading.value = false
    form.captchaCode = ''
    await loadCaptcha()
  }
}

function goRegister() {
  router.push('/register')
}
function goForget() {
  router.push('/forget')
}

loadCaptcha()

/**
 * 演示项目：进入登录页即预填默认角色的账号密码。
 * `applyDemoAccount` 内部有 `import.meta.env.DEV` 守卫，生产构建不会被填入，
 * 因此不会泄露到生产包体。
 */
applyDemoAccount(activeRole.value)
</script>

<template>
  <div class="login">
    <!-- ==================== 品牌区 ==================== -->
    <div class="login__brand">
      <div class="login__logo">
        <svg viewBox="0 0 32 32" width="36" height="36" fill="none" xmlns="http://www.w3.org/2000/svg">
          <path d="M16 28S4 20 4 12.5C4 8 7.5 4.5 11 4.5c1.7 0 3.6.7 5 2.7 1.4-2 3.3-2.7 5-2.7 3.5 0 7 3.5 7 8C28 20 16 28 16 28z" fill="white" />
          <path d="M9 14h4M19 14h4" stroke="#1D6FF2" stroke-width="1.5" stroke-linecap="round" />
        </svg>
      </div>
      <h1 class="login__title">银龄伴诊</h1>
      <p class="login__slogan">让每一次就医，都有温暖的陪伴</p>
    </div>

    <!-- ==================== 登录卡片 ==================== -->
    <div class="login__card">
      <!-- 角色快速选择 -->
      <section class="login__roles" aria-label="快速选择身份">
        <p class="login__roles-title">请选择您的身份</p>
        <div class="login__roles-grid">
          <button
            v-for="r in ROLE_CARDS"
            :key="r.role"
            :class="['login__rolecard', `is-${r.tone}`, { 'is-active': activeRole === r.role }]"
            type="button"
            @click="pickRole(r)"
          >
            <span class="login__rolecard-label">{{ r.label }}</span>
            <span class="login__rolecard-hint">{{ r.hint }}</span>
          </button>
        </div>
      </section>

      <el-divider class="login__divider">或使用账号密码登录</el-divider>

      <!-- 表单 -->
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        size="large"
        label-position="top"
        @submit.prevent="handleSubmit"
      >
        <el-form-item label="手机号 / 用户名" prop="username">
          <el-input
            v-model="form.username"
            placeholder="请输入手机号或用户名"
            clearable
            autocomplete="username"
          >
            <template #prefix>
              <svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.6">
                <rect x="2" y="2" width="12" height="12" rx="2" />
                <path d="M5 6h6M5 8h6M5 10h3" stroke-linecap="round" />
              </svg>
            </template>
          </el-input>
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            show-password
            autocomplete="current-password"
            @keyup.enter="handleSubmit"
          >
            <template #prefix>
              <svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.6">
                <rect x="2" y="6" width="12" height="9" rx="2" />
                <path d="M5 6V4a3 3 0 1 1 6 0v2" />
              </svg>
            </template>
          </el-input>
        </el-form-item>

        <el-form-item label="验证码" prop="captchaCode">
          <div class="login__captcha">
            <el-input
              v-model="form.captchaCode"
              placeholder="请输入右侧 4 位验证码"
              maxlength="4"
              @keyup.enter="handleSubmit"
            >
              <template #prefix>
                <svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.6">
                  <path d="M9 2L5 6M7 2L3 6" stroke-linecap="round" />
                  <path d="M14 5v6a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V7" />
                  <text x="8" y="13" text-anchor="middle" font-size="6" fill="currentColor" font-family="serif" stroke="none">A?</text>
                </svg>
              </template>
            </el-input>
            <div class="login__captcha-img" title="点击更换验证码" @click="loadCaptcha">
              <img v-if="captchaImage" :src="captchaImage" alt="验证码" />
              <span v-else class="login__captcha-empty">点击刷新</span>
            </div>
          </div>
        </el-form-item>

        <div class="login__row">
          <el-checkbox v-model="form.remember" size="default">7 天内自动登录</el-checkbox>
          <el-link type="primary" :underline="false" @click="goForget">忘记密码？</el-link>
        </div>

        <el-button
          type="primary"
          size="large"
          round
          class="login__submit"
          :loading="loading"
          @click="handleSubmit"
        >
          登 录
        </el-button>

        <div class="login__hint">
          还没有账号？
          <el-link type="primary" :underline="false" @click="goRegister">立即注册</el-link>
        </div>
      </el-form>
    </div>

    <!-- ==================== 底部免责 ==================== -->
    <footer class="login__footer">
      <p>银龄伴诊 · 软件工程课程设计</p>
      <p class="login__footer-tip">
        本平台仅提供就医陪诊与用药协同管理，不构成任何医疗诊断或用药建议。
      </p>
    </footer>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.login {
  display: flex;
  flex-direction: column;
  align-items: center;
  min-height: 100vh;
  padding: 0 $nl-space-4 $nl-space-6;
  background: linear-gradient(180deg, var(--nl-primary-ghost) 0%, var(--nl-bg) 320px);

  &__brand {
    display: flex;
    flex-direction: column;
    align-items: center;
    padding: $nl-space-8 0 $nl-space-5;
    text-align: center;
  }

  &__logo {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 64px;
    height: 64px;
    margin-bottom: $nl-space-3;
    background: var(--nl-primary-gradient);
    border-radius: 18px;
    box-shadow: var(--nl-shadow-card);
  }

  &__title {
    margin: 0;
    font-size: 26px;
    font-weight: 700;
    letter-spacing: 1px;
    color: var(--nl-text-1);
  }

  &__slogan {
    margin: 6px 0 0;
    font-size: 13px;
    color: var(--nl-text-2);
    letter-spacing: 0.5px;
  }

  &__card {
    width: 100%;
    max-width: 420px;
    padding: $nl-space-5;
    background: var(--nl-bg-card);
    border-radius: $nl-radius-card;
    box-shadow: var(--nl-shadow-card);
  }

  &__roles {
    margin-bottom: $nl-space-4;
  }

  &__roles-title {
    margin: 0 0 $nl-space-3;
    font-size: $nl-font-caption;
    color: var(--nl-text-2);
  }

  &__roles-grid {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: $nl-space-2;
  }

  &__rolecard {
    display: flex;
    flex-direction: column;
    gap: 2px;
    align-items: center;
    justify-content: center;
    padding: 10px 4px;
    background: var(--nl-bg-sunken);
    border: 1.5px solid transparent;
    border-radius: $nl-radius-btn;
    cursor: pointer;
    transition: all 0.18s;

    &:hover {
      transform: translateY(-1px);
      box-shadow: var(--nl-shadow-card);
    }

    &.is-primary.is-active { background: var(--nl-primary-light);  border-color: var(--nl-primary);  }
    &.is-success.is-active { background: var(--nl-success-bg);   border-color: var(--nl-success); }
    &.is-warning.is-active { background: var(--nl-warning-bg);   border-color: var(--nl-warning); }
    &.is-purple.is-active  { background: var(--nl-purple-bg);    border-color: var(--nl-purple);  }
  }

  &__rolecard-label {
    font-size: 13px;
    font-weight: 600;
    color: var(--nl-text-1);
  }

  &__rolecard-hint {
    font-size: 11px;
    line-height: 1.3;
    color: var(--nl-text-3);
    text-align: center;
    display: none; /* 卡片太小时隐藏，避免拥挤 */
  }

  &__divider {
    margin: $nl-space-3 0 $nl-space-4;

    :deep(.el-divider__text) {
      font-size: $nl-font-caption;
      color: var(--nl-text-3);
    }
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
    background: var(--nl-bg-sunken);
    border: 1px solid var(--nl-border);
    border-radius: $nl-radius-input;

    img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }
  }

  &__captcha-empty {
    font-size: 12px;
    color: var(--nl-text-3);
  }

  &__row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: $nl-space-4;
  }

  &__submit {
    width: 100%;
    font-size: 16px;
    font-weight: 500;
  }

  &__hint {
    margin-top: $nl-space-4;
    font-size: 13px;
    color: var(--nl-text-2);
    text-align: center;
  }

  &__footer {
    margin-top: auto;
    padding-top: $nl-space-5;
    text-align: center;

    p {
      margin: 4px 0;
      font-size: 12px;
      color: var(--nl-text-3);
    }
  }

  &__footer-tip {
    max-width: 360px;
    color: var(--nl-text-3);
  }
}

/* 桌面端卡片稍大 */
@media (min-width: 768px) {
  .login {
    &__rolecard-hint {
      display: block;
    }
  }
}
</style>
