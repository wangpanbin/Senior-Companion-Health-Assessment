<script setup>
/**
 * 模块占位组件。
 *
 * 用途：骨架阶段替代未实现的业务页面，把「模块归属 / 负责人 / 待实现功能 / 验收标准」
 * 直接显示在页面上，让 4 人团队各自清楚自己那一块要做什么。
 *
 * 用法：
 *   <ModulePlaceholder module="M4" title="陪诊订单"
 *     owner="B（后端主力）+ A（前端主力）"
 *     :features="[...]" :acceptance="[...]" />
 */
defineProps({
  /** 模块编号，如 M4 */
  module: { type: String, required: true },
  /** 模块名称 */
  title: { type: String, required: true },
  /** 负责人 */
  owner: { type: String, default: '' },
  /** 归属迭代 / 周次 */
  iteration: { type: String, default: '' },
  /** 前置依赖 */
  dependsOn: { type: String, default: '' },
  /** 待实现功能点 */
  features: { type: Array, default: () => [] },
  /** 验收标准 */
  acceptance: { type: Array, default: () => [] },
  /** 对应接口文档路径 */
  apiDoc: { type: String, default: '' }
})
</script>

<template>
  <div class="nl-placeholder">
    <div class="nl-placeholder__head">
      <el-tag type="success" effect="dark" size="large">{{ module }}</el-tag>
      <span class="nl-placeholder__name">{{ title }}</span>
      <el-tag v-if="iteration" type="info" effect="plain">{{ iteration }}</el-tag>
      <el-tag v-if="!owner" type="warning" effect="plain">尚未分配负责人</el-tag>
    </div>

    <el-descriptions :column="1" border size="small" class="mt-12">
      <el-descriptions-item v-if="owner" label="负责人">{{ owner }}</el-descriptions-item>
      <el-descriptions-item v-if="dependsOn" label="前置依赖">{{ dependsOn }}</el-descriptions-item>
      <el-descriptions-item v-if="apiDoc" label="接口文档">
        <code>{{ apiDoc }}</code>
      </el-descriptions-item>
    </el-descriptions>

    <div v-if="features.length" class="mt-16">
      <div class="nl-placeholder__desc">待实现功能</div>
      <ul class="nl-placeholder__list">
        <li v-for="(f, i) in features" :key="i">{{ f }}</li>
      </ul>
    </div>

    <div v-if="acceptance.length" class="mt-16">
      <div class="nl-placeholder__desc">验收标准（来自 plan.md）</div>
      <ul class="nl-placeholder__list">
        <li v-for="(a, i) in acceptance" :key="i">{{ a }}</li>
      </ul>
    </div>

    <el-alert
      class="mt-16"
      type="info"
      :closable="false"
      show-icon
      title="当前为工程骨架占位页，业务实现由对应模块负责人按迭代计划替换本组件。"
    />
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.nl-placeholder {
  &__head {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    align-items: center;
  }

  &__name {
    font-size: $font-size-lg;
    font-weight: 600;
    color: var(--nl-text-primary);
  }
}

.mt-12 {
  margin-top: 12px;
}

.mt-16 {
  margin-top: 16px;
}

code {
  padding: 2px 6px;
  font-family: Consolas, Monaco, monospace;
  background: rgb(31 39 51 / 6%);
  border-radius: 4px;
}
</style>
