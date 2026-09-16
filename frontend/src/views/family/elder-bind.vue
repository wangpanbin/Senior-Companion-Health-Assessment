<script setup>
/**
 * 绑定老人（M-19 · design.md §1.2 P1）
 *
 * - 两种绑定方式（Tab）：邀请码 / 手机号
 * - 身份证号加密存储 + 接口脱敏显示（design.md §6.2）
 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { NlPhoneShell, NlCard, NlNoticeBar } from '@/components'

const router = useRouter()
const tab = ref('invite')

const form = ref({
  inviteCode: '',
  phone: '',
  smsCode: '',
  elderName: '',
  relation: '父亲'
})

function next() {
  if (tab.value === 'invite') {
    if (form.value.inviteCode.length < 4) {
      ElMessage.warning('邀请码长度不足')
      return
    }
  } else {
    if (!/^1\d{10}$/.test(form.value.phone)) {
      ElMessage.warning('请输入正确的手机号')
      return
    }
    if (form.value.smsCode.length !== 4) {
      ElMessage.warning('请输入 4 位短信验证码')
      return
    }
  }
  ElMessage.success('绑定请求已提交，老人将通过短信确认')
  setTimeout(() => router.push('/family/elder'), 800)
}
</script>

<template>
  <NlPhoneShell :nav="{ title: '绑定老人' }">
    <NlNoticeBar>
      绑定后您可代老人预约陪诊 / 管理用药 / 查看就医记录，老人本人无需操作。
    </NlNoticeBar>

    <section class="tabs">
      <button :class="['tabs__btn', { 'is-active': tab === 'invite' }]" @click="tab = 'invite'">
        邀请码绑定
      </button>
      <button :class="['tabs__btn', { 'is-active': tab === 'sms' }]" @click="tab = 'sms'">
        手机号验证
      </button>
    </section>

    <NlCard>
      <el-form v-if="tab === 'invite'" size="large">
        <el-form-item label="邀请码" required>
          <el-input
            v-model="form.inviteCode"
            placeholder="请输入老人发给您的邀请码"
            maxlength="20"
          />
        </el-form-item>
        <p class="nl-caption nl-text-muted">
          邀请码由老人在自己的「我的 - 邀请家属」页面生成。
        </p>
      </el-form>

      <el-form v-else size="large">
        <el-form-item label="老人手机号" required>
          <el-input v-model="form.phone" placeholder="请输入老人绑定的手机号" maxlength="11" />
        </el-form-item>
        <el-form-item label="短信验证码" required>
          <div class="sms-row">
            <el-input v-model="form.smsCode" placeholder="4 位数字" maxlength="4" />
            <el-button>获取验证码</el-button>
          </div>
        </el-form-item>
        <el-form-item label="与老人关系">
          <el-radio-group v-model="form.relation">
            <el-radio-button label="父亲" />
            <el-radio-button label="母亲" />
            <el-radio-button label="配偶" />
            <el-radio-button label="其他" />
          </el-radio-group>
        </el-form-item>
      </el-form>
    </NlCard>

    <div class="submit-bar">
      <el-button type="primary" size="large" round class="submit-bar__btn" @click="next">
        下一步
      </el-button>
    </div>
  </NlPhoneShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.tabs {
  display: flex;
  gap: var(--nl-space-2);
  padding: 0 var(--nl-gutter);

  &__btn {
    flex: 1;
    padding: 12px 0;
    font-size: 14px;
    font-weight: 500;
    color: var(--nl-text-2);
    background: var(--nl-bg-card);
    border: 1px solid var(--nl-border);
    border-radius: 12px 12px 0 0;
    cursor: pointer;
    border-bottom: 0;

    &.is-active {
      color: var(--nl-primary);
      background: var(--nl-bg-card);
      border-bottom: 2px solid var(--nl-primary);
    }
  }
}

.sms-row {
  display: flex;
  gap: var(--nl-space-2);
  width: 100%;

  :deep(.el-input) {
    flex: 1;
  }
}

.submit-bar {
  padding: var(--nl-space-5) var(--nl-gutter);

  &__btn {
    width: 100%;
  }
}
</style>
