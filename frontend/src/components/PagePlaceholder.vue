<script setup>
/**
 * PagePlaceholder · 未实现页面占位
 *
 * 用在路由已挂载但页面尚未高保真化的场景，与 design.md §1 的页面清单 1:1 对齐
 * 显示页面编号 / 标题 / 简介 / 优先级，方便开发对照清单补齐
 */
import { NlCard, NlStatusChip, NlEmpty } from '@/components'

defineProps({
  /** 页面编号如 M-04 */
  code: { type: String, required: true },
  title: { type: String, required: true },
  /** 一句话目标 */
  goal: { type: String, default: '' },
  /** 优先级 P0 / P1 / P2 */
  priority: { type: String, default: 'P1' },
  /** 角色 */
  role: { type: String, default: '' },
  /** 涉及模块 */
  module: { type: String, default: '' },
  /** 接口文档路径 */
  api: { type: String, default: '' }
})
</script>

<template>
  <NlCard>
    <template #title>
      <div class="page-ph__title">
        <span class="page-ph__code">{{ code }}</span>
        <span>{{ title }}</span>
        <NlStatusChip :tone="priority === 'P0' ? 'warning' : priority === 'P1' ? 'info' : 'neutral'" :text="priority" />
        <el-tag v-if="role" size="small" effect="plain" type="info">{{ role }}</el-tag>
      </div>
    </template>
    <p v-if="goal" class="nl-body nl-text-muted">{{ goal }}</p>

    <div v-if="module || api" class="page-ph__meta">
      <div v-if="module"><strong>对应模块：</strong>{{ module }}</div>
      <div v-if="api"><strong>接口文档：</strong><code>{{ api }}</code></div>
    </div>

    <NlEmpty
      type="empty"
      title="该页面骨架待实现"
      description="按 design.md 规范与 P0 顺序推进，先完成组件库与基础页面"
    />
  </NlCard>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.page-ph {
  &__title {
    display: flex;
    flex-wrap: wrap;
    gap: var(--nl-space-2);
    align-items: center;
  }

  &__code {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    padding: 2px 8px;
    font-family: var(--nl-font-num);
    font-size: var(--nl-font-caption);
    font-weight: 600;
    color: var(--nl-primary);
    background: var(--nl-primary-light);
    border-radius: var(--nl-radius-chip);
  }

  &__meta {
    display: flex;
    flex-direction: column;
    gap: 4px;
    padding: var(--nl-space-3);
    margin-top: var(--nl-space-3);
    font-size: var(--nl-font-caption);
    color: var(--nl-text-2);
    background: var(--nl-bg-sunken);
    border-radius: var(--nl-radius-card);

    code {
      padding: 1px 6px;
      font-family: Consolas, Monaco, monospace;
      background: #fff;
      border-radius: 4px;
    }
  }
}
</style>
