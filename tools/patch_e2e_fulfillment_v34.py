# -*- coding: utf-8 -*-
"""更新 15-fulfillment.spec.js 里的大厅排序断言（V3.4 开放了 DISTANCE / TIME_MATCH）。

用法：python tools/patch_e2e_fulfillment_v34.py
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = ROOT / "frontend/e2e/specs/15-fulfillment.spec.js"

OLD = """  test('未开放的排序维度返回 400，不静默降级为推荐序', async () => {
    const { ctx, dispose } = await apiAsAccount('comp001')
    try {
      for (const sortBy of ['DISTANCE', 'TIME_MATCH', 'INCOME']) {
        const body = await (await ctx.get(`${API}/order/hall?sortBy=${sortBy}`)).json()
        expect(body.code, `sortBy=${sortBy} 必须 400`).toBe(400)
      }
    } finally {
      await dispose()
    }
  })"""

NEW = """  test('未开放的排序维度返回 400，不静默降级为推荐序', async () => {
    const { ctx, dispose } = await apiAsAccount('comp001')
    try {
      // V3.4 开放了 DISTANCE / TIME_MATCH（见 18-matching.spec.js），
      // 这里只留**仍未开放**的收益优先 —— 它缺真实结算金额口径（roadmap 决策 D8）。
      for (const sortBy of ['INCOME']) {
        const body = await (await ctx.get(`${API}/order/hall?sortBy=${sortBy}`)).json()
        expect(body.code, `sortBy=${sortBy} 必须 400`).toBe(400)
      }
    } finally {
      await dispose()
    }
  })"""


def main():
    text = SPEC.read_text(encoding="utf-8")
    if "V3.4 开放了 DISTANCE / TIME_MATCH" in text:
        print("已更新，跳过")
        return 0
    if OLD not in text:
        print("未找到旧断言块", file=sys.stderr)
        return 1
    text = text.replace(OLD, NEW, 1)
    SPEC.write_text(text, encoding="utf-8")
    print("15-fulfillment 的大厅排序断言已按 V3.4 更新")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
