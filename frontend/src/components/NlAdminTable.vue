<script setup>
/**
 * NlAdminTable · admin 端通用表格组件（desktop-adapt-v2 T-02）
 *
 * 依据：`docs/spec/desktop-adapt-v2.md` §4.1 + ticket 02
 *
 * 双形态：按 `useResponsive().isLg` 自动选
 *   isLg   (≥1280) → el-table + el-table-column 全列展示
 *   否则            → 卡片列表,字段按 `mobileFields` 顺序渲染,操作 slot 在卡片底部
 *
 * ## Props
 *
 * - data        : Array<Row>           表格数据
 * - columns     : Array<Column>        列定义,desktop 与 mobile 共用
 *     { key, label, width?, minWidth?, align?, formatter? }
 * - mobileFields: Array<string=key>    移动卡片要展示的字段（key 顺序即卡片顺序）
 * - rowKey      : string               行唯一键字段名,默认 'id'
 * - emptyText   : string               空态文案
 * - fill         : boolean              是否纵向铺满父容器（el-table height="100%"，
 *                                       表头固定、表体内部滚动；父容器需有确定高度）
 * - actionWidth : number|string         操作列宽度，默认 140
 *
 * ## Slots
 *
 * - cell-<key>  : 自定义单元格渲染(例:status chip)
 *                 桌面列 / 移动卡片共用,减少重复声明
 * - actions     : 操作按钮组
 *                 桌面放最右列、移动放卡片底部
 */
import { computed } from 'vue'
import { useResponsive } from '@/composables/useResponsive'
import NlEmpty from './NlEmpty.vue'

const props = defineProps({
  data: { type: Array, required: true },
  columns: { type: Array, required: true },
  mobileFields: { type: Array, default: () => [] },
  rowKey: { type: String, default: 'id' },
  emptyText: { type: String, default: '暂无数据' },
  fill: { type: Boolean, default: false },
  actionWidth: { type: [String, Number], default: 140 }
})

const { isLg } = useResponsive()

/** 桌面形态:columns 原样
 *  移动形态:从 columns 按 mobileFields 顺序取子集 */
const desktopColumns = computed(() => props.columns)
const mobileColumns = computed(() => {
  const map = new Map(props.columns.map((c) => [c.key, c]))
  return props.mobileFields.map((k) => map.get(k)).filter(Boolean)
})

/** 格式化单个单元格（formatter 优先，否则原值） */
function formatCell(col, row) {
  if (typeof col.formatter === 'function') return col.formatter(row[col.key], row)
  return row[col.key]
}
</script>

<template>
  <div class="nl-admin-table">
    <!-- ==================== Desktop · el-table 形态（≥1280） ==================== -->
    <div v-if="isLg" class="nl-admin-table__desktop">
      <el-table :data="data" :row-key="rowKey" :height="fill ? '100%' : undefined" stripe>
        <el-table-column
          v-for="col in desktopColumns"
          :key="col.key"
          :prop="col.key"
          :label="col.label"
          :width="col.width"
          :min-width="col.minWidth"
          :align="col.align"
        >
          <template #default="{ row }">
            <slot :name="`cell-${col.key}`" :row="row" :col="col">
              {{ formatCell(col, row) }}
            </slot>
          </template>
        </el-table-column>
        <!-- 操作列 -->
        <el-table-column v-if="$slots.actions" label="操作" :width="actionWidth" align="right">
          <template #default="{ row }">
            <slot name="actions" :row="row" />
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- ==================== Mobile · 卡片列表形态（<1280） ==================== -->
    <ol v-else class="nl-admin-table__cards">
      <li
        v-if="!data.length"
        class="nl-admin-table__empty"
      >
        <NlEmpty type="empty" :title="emptyText" />
      </li>
      <li
        v-for="row in data"
        v-else
        :key="row[rowKey]"
        class="nl-admin-table__card"
      >
        <div class="nl-admin-table__card-fields">
          <div v-for="col in mobileColumns" :key="col.key" class="nl-admin-table__card-field">
            <span class="nl-admin-table__card-label">{{ col.label }}</span>
            <span class="nl-admin-table__card-value">
              <slot :name="`cell-${col.key}`" :row="row" :col="col">
                {{ formatCell(col, row) }}
              </slot>
            </span>
          </div>
        </div>
        <div v-if="$slots.actions" class="nl-admin-table__card-actions">
          <slot name="actions" :row="row" />
        </div>
      </li>
    </ol>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.nl-admin-table {
  &__cards {
    padding: 0;
    margin: 0;
    list-style: none;
  }

  &__card {
    padding: $nl-space-3;
    margin-bottom: $nl-space-3;
    background: var(--nl-bg-card);
    border: 1px solid var(--nl-border);
    border-radius: $nl-radius-card;
    box-shadow: var(--nl-shadow-card);
  }

  &__card-fields {
    display: flex;
    flex-direction: column;
    gap: $nl-space-2;
  }

  &__card-field {
    display: flex;
    gap: $nl-space-2;
    align-items: baseline;
    font-size: $nl-font-body;
  }

  &__card-label {
    flex-shrink: 0;
    width: 72px;
    font-size: $nl-font-caption;
    color: var(--nl-text-2);
  }

  &__card-value {
    flex: 1;
    color: var(--nl-text-1);
  }

  &__card-actions {
    display: flex;
    gap: $nl-space-2;
    align-items: center;
    padding-top: $nl-space-2;
    margin-top: $nl-space-2;
    border-top: 1px dashed var(--nl-divider);
  }

  &__empty {
    list-style: none;
  }
}
</style>