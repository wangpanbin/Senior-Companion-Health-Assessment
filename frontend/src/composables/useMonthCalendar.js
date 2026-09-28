import { computed, toValue } from 'vue'

/**
 * 月历网格 composable（收口迭代 E2）。
 *
 * 从 elder/medication.vue 与 family/medication.vue 的两份重复实现上提：
 * 周一为一周开始（offset = (first.getDay() + 6) % 7），首行按 offset 补 null，
 * 网格总长补齐到 7 的倍数 —— 周视图直接取前 7 格即可对齐星期表头。
 *
 * @param {import('vue').MaybeRefOrGetter<number>} year
 * @param {import('vue').MaybeRefOrGetter<number>} month 1-12
 * @returns {{ offset: import('vue').ComputedRef<number>, cells: import('vue').ComputedRef<Array<number|null>> }}
 */
export function useMonthCalendar(year, month) {
  const offset = computed(() => {
    const first = new Date(toValue(year), toValue(month) - 1, 1)
    return (first.getDay() + 6) % 7 // 周一为一周开始
  })

  const cells = computed(() => {
    const y = toValue(year)
    const m = toValue(month)
    const daysInMonth = new Date(y, m, 0).getDate()
    const grid = Array(offset.value).fill(null)
    for (let d = 1; d <= daysInMonth; d++) grid.push(d)
    while (grid.length % 7 !== 0) grid.push(null)
    return grid
  })

  return { offset, cells }
}
