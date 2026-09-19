<script setup>
/**
 * 静态说明页 · 服务协议 / 隐私政策（M2）
 *
 * 两类文档共享同一份移动端阅读布局，由路由 props 区分内容；
 * 页面只展示静态条款，不请求接口，也不展示任何个人敏感信息。
 */
import { computed } from 'vue'
import { NlPhoneShell, NlMobileOnlyPage, NlCard } from '@/components'

const props = defineProps({
  type: { type: String, default: 'service' }
})

const DOCUMENTS = {
  service: {
    title: '服务协议',
    version: '版本 2.0',
    updateTime: '本版本现行有效',
    intro: '欢迎使用银龄伴诊。请在使用预约、陪诊记录与用药协同服务前，阅读并理解以下内容。',
    sections: [
      {
        title: '服务范围',
        paragraphs: [
          '平台提供陪诊预约、订单进度记录、用药计划提醒及站内通知等信息协同服务。',
          '平台不提供医疗诊断、处方开具或用药剂量建议；具体诊疗请遵循医疗机构及执业医师的意见。'
        ]
      },
      {
        title: '账号与订单',
        paragraphs: [
          '请使用本人真实、有效的信息注册并妥善保管账号。家属可在授权范围内为关联老人创建或管理服务事项。',
          '订单状态、服务时间与文字地址以订单页面展示为准；如需变更，请通过订单中的规范流程操作。'
        ]
      },
      {
        title: '使用规范',
        paragraphs: [
          '请勿发布违法、虚假、侮辱性信息，或利用平台侵害他人合法权益。',
          '陪诊服务产生的费用以订单记录为依据，一期仅提供线上记账与线下结算，不提供在线支付。'
        ]
      },
      {
        title: '协议更新',
        paragraphs: [
          '我们可能根据服务变化更新本协议，并在页面以适当方式提示。继续使用服务即表示您接受更新后的内容。'
        ]
      }
    ]
  },
  privacy: {
    title: '隐私政策',
    version: '隐私保护说明',
    updateTime: '本版本现行有效',
    intro: '我们重视您的个人信息安全，并遵循必要、合理的原则处理与陪诊服务相关的信息。',
    sections: [
      {
        title: '我们收集的信息',
        paragraphs: [
          '为完成账号、陪诊预约和服务记录，我们会在必要范围内处理账号资料、老人关联关系、订单及用药计划信息。',
          '不同功能所需信息不同；未提供必要信息时，部分功能可能无法正常使用。'
        ]
      },
      {
        title: '信息如何使用',
        paragraphs: [
          '信息仅用于身份识别、订单履行、服务通知、争议处理和系统安全保障等与服务直接相关的用途。',
          '页面展示、接口返回和业务日志会按场景进行脱敏处理，避免暴露完整手机号、身份证号等敏感字段。'
        ]
      },
      {
        title: '信息保护与共享',
        paragraphs: [
          '密码采用不可逆加密方式保存；我们采取合理的访问控制措施保护数据安全。',
          '除取得您的明确授权、履行法定义务或保护您和他人的合法权益外，我们不会向无关第三方提供您的个人信息。'
        ]
      },
      {
        title: '您的权利',
        paragraphs: [
          '您可以在服务允许的范围内查看、更新相关资料，或通过平台反馈渠道咨询个人信息处理事项。',
          '如您注销账号，我们将依照适用规则处理相关信息；法律法规要求留存的信息除外。'
        ]
      }
    ]
  }
}

const document = computed(() => DOCUMENTS[props.type] || DOCUMENTS.service)
</script>

<template>
  <NlMobileOnlyPage>
    <NlPhoneShell :nav="{ title: document.title }">
      <article class="legal-page">
        <header class="legal-page__intro">
          <p class="legal-page__version">{{ document.version }}</p>
          <h2>{{ document.title }}</h2>
          <p>{{ document.intro }}</p>
          <span>{{ document.updateTime }}</span>
        </header>

        <NlCard
          v-for="(section, index) in document.sections"
          :key="section.title"
          class="legal-section"
          :padding="20"
        >
          <div class="legal-section__heading">
            <span>{{ String(index + 1).padStart(2, '0') }}</span>
            <h3>{{ section.title }}</h3>
          </div>
          <p
            v-for="paragraph in section.paragraphs"
            :key="paragraph"
            class="legal-section__paragraph"
          >
            {{ paragraph }}
          </p>
        </NlCard>

        <p class="legal-page__footer">银龄伴诊将持续以最小必要原则保护您的信息安全。</p>
      </article>
    </NlPhoneShell>
  </NlMobileOnlyPage>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.legal-page {
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-4);
  padding: 0 var(--nl-gutter) var(--nl-space-6);

  &__intro {
    padding: var(--nl-space-2) var(--nl-space-1) var(--nl-space-1);

    h2 {
      margin: var(--nl-space-2) 0;
      font-size: var(--nl-font-h1);
      line-height: var(--nl-lh-h1);
      color: var(--nl-text-1);
    }

    p:not(.legal-page__version) {
      margin: 0;
      font-size: var(--nl-font-body);
      line-height: var(--nl-lh-body);
      color: var(--nl-text-2);
    }

    span {
      display: inline-block;
      margin-top: var(--nl-space-2);
      font-size: var(--nl-font-caption);
      color: var(--nl-text-3);
    }
  }

  &__version {
    margin: 0;
    font-size: var(--nl-font-caption);
    font-weight: 600;
    color: var(--nl-primary);
  }

  &__footer {
    margin: 0;
    padding: 0 var(--nl-space-2);
    font-size: var(--nl-font-caption);
    line-height: var(--nl-lh-caption);
    text-align: center;
    color: var(--nl-text-3);
  }
}

.legal-section {
  gap: var(--nl-space-3);

  &__heading {
    display: flex;
    gap: var(--nl-space-2);
    align-items: center;

    span {
      font-family: var(--nl-font-num);
      font-size: var(--nl-font-caption);
      font-weight: 700;
      color: var(--nl-primary);
    }

    h3 {
      margin: 0;
      font-size: var(--nl-font-h3);
      line-height: var(--nl-lh-h3);
      color: var(--nl-text-1);
    }
  }

  &__paragraph {
    margin: 0;
    font-size: var(--nl-font-body);
    line-height: var(--nl-lh-body);
    color: var(--nl-text-2);
  }
}
</style>
