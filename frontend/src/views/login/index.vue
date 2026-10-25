<script setup>
/**
 * 登录页（M-01 · docs/design/design.md §1.2）
 *
 * - 顶部品牌区（医疗蓝渐变 + 心形 SVG logo + slogan）
 * - 角色快速选择（4 角色卡片，选中切换对应演示账号）
 * - 表单：手机号 + 密码 + 图形验证码
 * - 底部：忘记密码 + 注册 + 免责文案
 * - 演示账号：仅 DEV 环境可见，避免生产包体里带默认口令
 */
import { reactive, ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore, ROLE_LABELS } from '@/store/modules/user'
import { getCaptcha } from '@/api/auth'
import { resolveRedirect } from '@/router/index'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const formRef = ref()
/**
 * 提交中的 in-flight 锁。
 *
 * ⚠️ 必须**同步**置位（在任何 await 之前）：若等表单校验通过后才置位，
 * 两次调用会一起穿过 await，锁就形同虚设。而登录接口的验证码是**一次性**的
 * （后端 CaptchaServiceImpl 用 Redis getAndDelete 取出即删），
 * 第二次请求必然失败 —— 用户看到的是「验证码错误或已过期」，
 * 而密码其实是对的。
 */
const submitting = ref(false)
const captchaImage = ref('')

const form = reactive({
  username: '',
  password: '',
  captchaKey: '',
  captchaCode: '',
  /**
   * 「7 天内自动登录」默认勾选。
   *
   * <p>勾选 → 令牌存 localStorage（配合 7 天有效的 refreshToken，关掉浏览器再打开仍在）；
   * 不勾选 → 存 sessionStorage（关掉标签页即失效，更适合共用设备）。</p>
   *
   * <p>⚠️ 默认值必须是 <b>true</b>：这是本系统改动前的既有行为（令牌一律在 localStorage），
   * 默认关掉会让「换个标签页 / 从桌面图标重开」都要重新登录 —— 对要反复用的陪诊 App 是倒退。
   * 想更安全可以主动取消勾选。</p>
   */
  remember: true
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
 *
 * ⚠️ 卡片**不参与鉴权**：最终角色由后端按账号查库决定
 * （后端 LoginDTO 根本没有 role 字段，此前前端传的那个 role 会被 Jackson 静默丢弃）。
 * 所以卡片的作用只有一个 —— 省掉演示时手打账号。
 */
const ROLE_CARDS = [
  { role: 'ELDER',     label: '老年患者', hint: '只读视图 · 字号最大', tone: 'success', account: 'elder001' },
  { role: 'FAMILY',    label: '家属',     hint: '下单 · 绑定老人 · 看进度', tone: 'primary', account: 'fam001' },
  { role: 'COMPANION', label: '陪诊员',   hint: '抢单 · 6 节点打卡 · 收入', tone: 'warning', account: 'comp001' },
  { role: 'ADMIN',     label: '管理员',   hint: '数据看板 · 审资质 · 调纠纷', tone: 'purple', account: 'admin' }
]

const activeRole = ref('FAMILY')

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
    // 口令来自 .env.development 的 VITE_DEMO_PASSWORD，不写死在源码里：
    // 演示账号只对本地种子数据有意义，但明文进版本库仍会成为「默认口令」的
    // 事实来源。生产构建里 import.meta.env.DEV 为 false，这段不会执行。
    form.password = import.meta.env.VITE_DEMO_PASSWORD || ''
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
    // ⚠️ 不能只把图置空就完事：用户只会看到一个「点击刷新」的灰框，
    // 完全不知道是验证码服务挂了还是自己手滑。给一句能行动的话。
    ElMessage.error('验证码加载失败，请点击图片重试')
  }
}

async function handleSubmit() {
  // ⚠️ in-flight 锁必须在任何 await 之前同步置位（见 submitting 的注释）
  if (submitting.value) return
  submitting.value = true
  try {
    const valid = await formRef.value?.validate().catch(() => false)
    if (!valid) return
    await userStore.login({
      username: form.username,
      password: form.password,
      captchaKey: form.captchaKey,
      captchaCode: form.captchaCode,
      // 「7 天内自动登录」：true → localStorage（配合 7 天有效的 refreshToken），
      // false → sessionStorage（关掉标签页即失效）。
      // 此前 form.remember 从未被读取，这个复选框点了没反应。
      remember: form.remember
    })
    ElMessage.success('登录成功')
    // ⚠️ redirect 必须过白名单（resolveRedirect）：它来自 URL query，
    // 可能指向站外地址、不存在的路径，或当前角色无权访问的页（如 FAMILY 指向后台）。
    // 不校验的话，登录后会被弹到一个无关页面或 404，用户以为登录出了问题。
    router.push(resolveRedirect(route.query.redirect, userStore.role))
  } catch {
    // 错误提示已在 axios 拦截器统一处理
  } finally {
    submitting.value = false
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
            @keydown.enter.prevent="handleSubmit"
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
              @keydown.enter.prevent="handleSubmit"
            >
              <template #prefix>
                <svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.6">
                  <path d="M9 2L5 6M7 2L3 6" stroke-linecap="round" />
                  <path d="M14 5v6a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V7" />
                  <text x="8" y="13" text-anchor="middle" font-size="6" fill="currentColor" font-family="serif" stroke="none">A?</text>
                </svg>
              </template>
            </el-input>
            <div role="button" tabindex="0" class="login__captcha-img" title="点击更换验证码" @keydown.enter.prevent="loadCaptcha" @keydown.space.prevent="loadCaptcha" @click="loadCaptcha">
              <img v-if="captchaImage" :src="captchaImage" alt="验证码" />
              <span v-else class="login__captcha-empty">点击刷新</span>
            </div>
          </div>
        </el-form-item>

        <div class="login__row">
          <!--
            「记住我」不是死控件：勾选 → 令牌存 localStorage（配合 7 天有效的 refreshToken，
            关掉浏览器再打开仍在）；不勾选 → 存 sessionStorage（关掉标签页即失效）。
            实现见 frontend/src/utils/auth.js 的 targetStore()。
          -->
          <el-checkbox
            v-model="form.remember"
            size="default"
            title="勾选后 7 天内免登录（令牌保存在本机浏览器）"
          >
            7 天内自动登录
          </el-checkbox>
          <el-link type="primary" underline="never" @click="goForget">忘记密码？</el-link>
        </div>

        <!--
          提交按钮绑 :loading —— Element Plus 在 loading 时会自动禁用按钮。
          ⚠️ 但真正防住「重复提交」的是 handleSubmit 里的 submitting 锁：
          它在第一次 await 之前同步置位，两次调用只会有一次真正发出请求。
        -->
        <el-button
          type="primary"
          size="large"
          round
          class="login__submit"
          :loading="submitting"
          @click="handleSubmit"
        >
          登 录
        </el-button>

        <div class="login__hint">
          还没有账号？
          <el-link type="primary" underline="never" @click="goRegister">立即注册</el-link>
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
    font-size: var(--nl-font-caption);
    color: var(--nl-text-2);
    letter-spacing: 0.5px;
  }

  &__card {
    width: 100%;
    max-width: 420px;
    padding: $nl-space-5;
    background: var(--nl-bg-card);
    border: 1px solid var(--nl-border);
    border-radius: $nl-radius-card;
  }

  &__roles {
    margin-bottom: $nl-space-4;
  }

  &__roles-title {
    margin: 0 0 $nl-space-3;
    font-size: var(--nl-font-caption);
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
      border-color: var(--nl-border-strong);
    }

    &.is-primary.is-active { background: var(--nl-primary-light);  border-color: var(--nl-primary);  }
    &.is-success.is-active { background: var(--nl-success-bg);   border-color: var(--nl-success); }
    &.is-warning.is-active { background: var(--nl-warning-bg);   border-color: var(--nl-warning); }
    &.is-purple.is-active  { background: var(--nl-purple-bg);    border-color: var(--nl-purple);  }
  }

  &__rolecard-label {
    font-size: var(--nl-font-caption);
    font-weight: 600;
    color: var(--nl-text-1);
  }

  &__rolecard-hint {
    font-size: var(--nl-font-caption);
    line-height: 1.3;
    color: var(--nl-text-2);
    text-align: center;
    display: none; /* 卡片太小时隐藏，避免拥挤 */
  }

  &__divider {
    margin: $nl-space-3 0 $nl-space-4;

    :deep(.el-divider__text) {
      font-size: var(--nl-font-caption);
      color: var(--nl-text-2);
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
    font-size: var(--nl-font-caption);
    color: var(--nl-text-2);
  }

  &__row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: $nl-space-4;
  }

  &__submit {
    width: 100%;
    font-size: var(--nl-font-h3);
    font-weight: 500;
  }

  &__hint {
    margin-top: $nl-space-4;
    font-size: var(--nl-font-caption);
    color: var(--nl-text-2);
    text-align: center;
  }

  &__footer {
    margin-top: auto;
    padding-top: $nl-space-5;
    text-align: center;

    p {
      margin: 4px 0;
      font-size: var(--nl-font-caption);
      color: var(--nl-text-2);
    }
  }

  &__footer-tip {
    max-width: 360px;
    color: var(--nl-text-2);
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
