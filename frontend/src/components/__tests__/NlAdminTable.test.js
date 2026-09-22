/**
 * NlAdminTable 单测
 *
 * 覆盖双形态切换 + 卡片 fallback 字段渲染 + 空态。
 *
 * 设计要点：
 * - 组件根据 useResponsive().isLg 选 el-table 容器还是 ol 卡片容器
 * - 测试通过 vi.mock 替换 useResponsive，避免真实 matchMedia 干扰
 * - el-table / el-table-column 用 stubs 替换，绕过 element-plus jsdom 限制
 *   （element-plus 内部依赖 ResizeObserver，jsdom 不自带）
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { ref } from 'vue'

// mock useResponsive：测试里手动控制返回值
const useResponsiveMock = vi.fn()
vi.mock('@/composables/useResponsive', () => ({
  useResponsive: () => useResponsiveMock()
}))

// 动态 import：在 mock 注册后加载组件，确保它读到的是 mock 后的 useResponsive
const { default: NlAdminTable } = await import('@/components/NlAdminTable.vue')

function setResponsive(tier) {
  const map = {
    lg: { isLg: true, isMd: false, isSm: false, isXsPhone: false, isLandscapePhone: false },
    md: { isLg: false, isMd: true, isSm: false, isXsPhone: false, isLandscapePhone: false },
    sm: { isLg: false, isMd: false, isSm: true, isXsPhone: false, isLandscapePhone: false }
  }
  const r = map[tier]
  // ⚠️ 必须返回真正的 Vue ref，模板里 v-if 才能正确解包 .value
  //    之前用 plain {value: bool} 对象，Vue 不识别为 ref，模板拿到对象本身(truthy)→ 永远 true
  useResponsiveMock.mockReturnValue({
    isLg: ref(r.isLg),
    isMd: ref(r.isMd),
    isSm: ref(r.isSm),
    isXsPhone: ref(r.isXsPhone),
    isLandscapePhone: ref(r.isLandscapePhone),
    breakpoint: ref(tier)
  })
}

/** element-plus stubs：避免 jsdom 下 ResizeObserver 等 API 缺失
 *
 * 重要：ElTable / ElTableColumn 的 stub 不渲染 default slot —— 它们没法模拟
 * el-table 内部的 row scope，传 row 进去会因 row[col.key] 为 undefined 报错。
 * 这意味着 desktop 形态下的 cell 渲染细节由浏览器 E2E 验证，单测只断言
 * 「desktop 容器存在」与「mobile 形态的卡片字段渲染 + 操作 slot」。
 */
const stubs = {
  ElTable: { template: '<div class="el-table-stub"><slot /></div>' },
  ElTableColumn: {
    props: ['label', 'prop', 'width', 'minWidth', 'fixed', 'align'],
    template: '<div class="el-table-column-stub" :data-label="label"></div>'
  },
  ElButton: {
    props: ['size', 'type'],
    template: '<button class="el-button-stub" @click="$emit(\'click\')"><slot /></button>'
  },
  NlEmpty: {
    props: ['type', 'title', 'description'],
    template: '<div class="nl-empty-stub" :data-type="type"><span>{{ title }}</span></div>'
  }
}

const sampleData = [
  {
    id: 1,
    orderNo: 'O20260922001',
    elderName: '张三',
    hospital: '市第一医院',
    companionName: '李四',
    fee: 128,
    status: 'PENDING',
    statusLabel: '待接单',
    createTime: '2026-09-22 10:00:00'
  },
  {
    id: 2,
    orderNo: 'O20260922002',
    elderName: '王五',
    hospital: '中心医院',
    companionName: '赵六',
    fee: 256,
    status: 'COMPLETED',
    statusLabel: '已完成',
    createTime: '2026-09-22 11:00:00'
  }
]

const sampleColumns = [
  { key: 'orderNo', label: '订单号', width: 180 },
  { key: 'elderName', label: '就诊人', width: 100 },
  { key: 'hospital', label: '医院', minWidth: 180 },
  { key: 'companionName', label: '陪诊员', width: 100 },
  { key: 'fee', label: '服务费', width: 100 },
  { key: 'status', label: '状态', width: 100 },
  { key: 'createTime', label: '创建时间', width: 160 }
]

describe('NlAdminTable · 断点驱动双形态', () => {
  beforeEach(() => {
    useResponsiveMock.mockReset()
  })

  it('isLg（≥1280）渲染 desktop 容器', () => {
    setResponsive('lg')
    const wrapper = mount(NlAdminTable, {
      props: {
        data: sampleData,
        columns: sampleColumns,
        mobileFields: ['orderNo', 'elderName', 'status', 'fee']
      },
      global: { stubs }
    })
    expect(wrapper.find('.nl-admin-table__desktop').exists()).toBe(true)
    expect(wrapper.find('.nl-admin-table__cards').exists()).toBe(false)
  })

  it('isMd（1024-1279）渲染 mobile 卡片容器', () => {
    setResponsive('md')
    const wrapper = mount(NlAdminTable, {
      props: {
        data: sampleData,
        columns: sampleColumns,
        mobileFields: ['orderNo', 'elderName', 'status', 'fee']
      },
      global: { stubs }
    })
    expect(wrapper.find('.nl-admin-table__desktop').exists()).toBe(false)
    expect(wrapper.find('.nl-admin-table__cards').exists()).toBe(true)
  })

  it('isSm（<1024）同样渲染 mobile 卡片容器', () => {
    setResponsive('sm')
    const wrapper = mount(NlAdminTable, {
      props: {
        data: sampleData,
        columns: sampleColumns,
        mobileFields: ['orderNo']
      },
      global: { stubs }
    })
    expect(wrapper.find('.nl-admin-table__cards').exists()).toBe(true)
  })
})

describe('NlAdminTable · mobile 卡片渲染', () => {
  beforeEach(() => {
    useResponsiveMock.mockReset()
  })

  it('按 mobile-fields 顺序渲染字段', () => {
    setResponsive('md')
    const wrapper = mount(NlAdminTable, {
      props: {
        data: sampleData,
        columns: sampleColumns,
        mobileFields: ['orderNo', 'elderName', 'status', 'fee']
      },
      global: { stubs }
    })
    const cards = wrapper.findAll('.nl-admin-table__card')
    expect(cards.length).toBe(2)
    // 默认走 row[col.key]，所以 status 字段渲染 'PENDING'（枚举值）而非 statusLabel '待接单'
    // 想渲染 statusLabel 需要在调用方传 cell-status slot 替换
    expect(cards[0].text()).toContain('O20260922001')
    expect(cards[0].text()).toContain('张三')
    expect(cards[0].text()).toContain('PENDING')
    expect(cards[1].text()).toContain('O20260922002')
    expect(cards[1].text()).toContain('王五')
    expect(cards[1].text()).toContain('COMPLETED')
  })

  it('调用方传 cell-<key> slot 可自定义单元格渲染（如 statusLabel）', () => {
    setResponsive('md')
    const wrapper = mount(NlAdminTable, {
      props: {
        data: sampleData,
        columns: sampleColumns,
        mobileFields: ['orderNo', 'status']
      },
      slots: {
        'cell-status': '<span class="custom-status">{{ row.statusLabel }}</span>'
      },
      global: { stubs }
    })
    expect(wrapper.find('.custom-status').exists()).toBe(true)
    expect(wrapper.text()).toContain('待接单')
    expect(wrapper.text()).toContain('已完成')
    // 默认 PENDING / COMPLETED 不再出现
    expect(wrapper.text()).not.toContain('PENDING')
  })

  it('mobile 卡片渲染字段标签与值', () => {
    setResponsive('md')
    const wrapper = mount(NlAdminTable, {
      props: {
        data: sampleData,
        columns: sampleColumns,
        mobileFields: ['orderNo', 'elderName']
      },
      global: { stubs }
    })
    const labels = wrapper.findAll('.nl-admin-table__card-label')
    const values = wrapper.findAll('.nl-admin-table__card-value')
    // 2 行数据 × 2 字段 = 4 个标签 + 4 个值
    expect(labels.length).toBe(4)
    expect(values.length).toBe(4)
    // 第一个字段标签是 "订单号"
    expect(labels[0].text()).toBe('订单号')
    expect(values[0].text()).toBe('O20260922001')
  })

  it('mobile 卡片操作 slot 渲染在卡片底部', () => {
    setResponsive('md')
    const wrapper = mount(NlAdminTable, {
      props: {
        data: sampleData,
        columns: sampleColumns,
        mobileFields: ['orderNo']
      },
      slots: {
        actions: '<button class="custom-action-stub">查看</button>'
      },
      global: { stubs }
    })
    const cardActions = wrapper.findAll('.nl-admin-table__card-actions')
    expect(cardActions.length).toBe(2)
    expect(cardActions[0].find('.custom-action-stub').exists()).toBe(true)
  })

  it('formatter 用于金额 / 时间格式化', () => {
    setResponsive('md')
    const wrapper = mount(NlAdminTable, {
      props: {
        data: sampleData,
        columns: [
          { key: 'orderNo', label: '订单号' },
          { key: 'fee', label: '服务费', formatter: (v) => `¥${Number(v).toFixed(2)}` }
        ],
        mobileFields: ['orderNo', 'fee']
      },
      global: { stubs }
    })
    expect(wrapper.text()).toContain('¥128.00')
    expect(wrapper.text()).toContain('¥256.00')
  })
})

describe('NlAdminTable · 空态', () => {
  beforeEach(() => {
    useResponsiveMock.mockReset()
  })

  it('desktop 空数据仍渲染 el-table 容器', () => {
    setResponsive('lg')
    const wrapper = mount(NlAdminTable, {
      props: { data: [], columns: sampleColumns, mobileFields: ['orderNo'] },
      global: { stubs }
    })
    expect(wrapper.find('.nl-admin-table__desktop').exists()).toBe(true)
  })

  it('mobile 空数据走移动空态（NlEmpty）', () => {
    setResponsive('md')
    const wrapper = mount(NlAdminTable, {
      props: { data: [], columns: sampleColumns, mobileFields: ['orderNo'] },
      global: { stubs }
    })
    expect(wrapper.find('.nl-admin-table__empty').exists()).toBe(true)
    expect(wrapper.find('.nl-empty-stub').exists()).toBe(true)
  })

  it('空态文案由 emptyText 控制', () => {
    setResponsive('md')
    const wrapper = mount(NlAdminTable, {
      props: {
        data: [],
        columns: sampleColumns,
        mobileFields: ['orderNo'],
        emptyText: '当前筛选条件下没有订单'
      },
      global: { stubs }
    })
    expect(wrapper.find('.nl-empty-stub').attributes('title')).toBe(undefined)
    // 我们的 stub 只渲染 title 文本,所以直接断言
    expect(wrapper.text()).toContain('当前筛选条件下没有订单')
  })
})