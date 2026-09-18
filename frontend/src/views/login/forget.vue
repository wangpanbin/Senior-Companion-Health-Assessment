<script setup>
/**
 * 找回密码（M-03 · design.md §1.2 P2）
 *
 * ⚠️ 本页从「假表单」改为「引导页」，原因是后端**没有自助重置密码的能力**：
 *
 *   一期范围内不含短信服务商，而 `docs/api/01-auth-user.md` 只提供了两个改密入口：
 *     · `PUT /api/auth/password`          —— 需**已登录** + 校验原密码
 *     · `POST /api/admin/user/{id}/reset-password` —— 管理员重置
 *   没有「凭手机号 + 验证码重置」这个接口。
 *
 *   原实现保留了一个手机号 + 短信验证码表单，点「重置密码」直接 `ElMessage.success`
 *   就跳走了 —— 不调任何接口。这比没有这个页面更糟：用户会以为密码真的改了，
 *   回到登录页用新密码登不进去，然后开始怀疑是自己记错了密码。
 *
 *   所以这里改成如实说明 + 给出真正能走通的路径，不保留任何「假成功」按钮。
 */
import { useRouter } from 'vue-router'
import { NlPageShell, NlCard, NlNoticeBar } from '@/components'

const router = useRouter()
</script>

<template>
  <NlPageShell title="找回密码">
    <NlNoticeBar tone="warning">
      本平台暂未开通自助重置密码，请按下方说明联系我们处理。
    </NlNoticeBar>

    <NlCard title="为什么不能自助重置">
      <p class="nl-body para">
        自助重置需要向注册手机号下发短信验证码来确认身份，而本系统一期范围内未接入短信服务。
        为避免在缺少身份核验手段的情况下放开密码重置入口，该功能暂不提供。
      </p>
    </NlCard>

    <NlCard title="可以这样做">
      <ol class="steps">
        <li class="step">
          <span class="step__no">1</span>
          <div class="step__body">
            <div class="nl-h3">还记得原密码</div>
            <p class="nl-caption nl-text-muted">
              直接登录后，在「我的 → 修改密码」里自行修改。为保障账号安全，
              修改后所有已登录设备都会退出，需要用新密码重新登录。
            </p>
          </div>
        </li>
        <li class="step">
          <span class="step__no">2</span>
          <div class="step__body">
            <div class="nl-h3">完全忘记密码</div>
            <p class="nl-caption nl-text-muted">
              请联系平台管理员，说明注册时使用的用户名或手机号，由管理员在管理后台
              重置为新密码后告知您。管理员重置后同样需要重新登录。
            </p>
          </div>
        </li>
      </ol>
    </NlCard>

    <div class="actions">
      <el-button type="primary" size="large" round class="actions__btn" @click="router.push('/login')">
        返回登录
      </el-button>
    </div>
  </NlPageShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.para {
  margin: 0;
  color: var(--nl-text-2);
}

.steps {
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-3);
  padding: 0;
  margin: 0;
  list-style: none;
}

.step {
  display: flex;
  gap: var(--nl-space-3);
  align-items: flex-start;

  &__no {
    display: inline-flex;
    flex-shrink: 0;
    align-items: center;
    justify-content: center;
    width: 22px;
    height: 22px;
    font-size: 12px;
    font-weight: 600;
    color: var(--nl-primary);
    background: var(--nl-primary-light);
    border-radius: 50%;
  }

  &__body {
    flex: 1;
    min-width: 0;

    .nl-caption {
      margin: 4px 0 0;
      line-height: 1.6;
    }
  }
}

.actions {
  padding: var(--nl-space-5) var(--nl-gutter);

  &__btn {
    width: 100%;
    font-size: 16px;
  }
}
</style>
