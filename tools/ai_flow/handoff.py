# -*- coding: utf-8 -*-
"""跨环节交接卡。

四个环节（门禁定位 / 文档同步 / 提交准备 / 项目切换）之间不靠对话记忆衔接，
靠一张固定格式的交接卡：上一环写「状态」，下一环读「下一步」。

用法
----
    python tools/ai_flow/handoff.py new --loop L1 --summary "改完订单接口"
    python tools/ai_flow/handoff.py latest
    python tools/ai_flow/handoff.py list

卡片落在 .workbuddy/handoff/（该目录已被 .gitignore 忽略，不入库）。
零依赖（只用标准库）。
"""

from __future__ import annotations

import argparse
import datetime
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = ROOT / ".workbuddy" / "handoff"

LOOPS = {
    "L1": "文档同步环",
    "L2": "门禁定位环",
    "L3": "提交准备环",
    "L4": "项目切换环",
}


def git_head():
    try:
        return subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            cwd=str(ROOT),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        ).stdout.strip()
    except Exception:
        return "?"


def git_branch():
    try:
        return subprocess.run(
            ["git", "branch", "--show-current"],
            cwd=str(ROOT),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        ).stdout.strip()
    except Exception:
        return "?"


def cards():
    if not OUT_DIR.is_dir():
        return []
    return sorted(OUT_DIR.glob("*.md"))


def build(loop, summary, verdict, next_step, risks):
    now = datetime.datetime.now()
    stamp = now.strftime("%Y%m%d-%H%M%S")
    name = stamp + "-" + loop + ".md"
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    body = []
    body.append("# 交接卡 · " + loop + " " + LOOPS.get(loop, ""))
    body.append("")
    body.append("- 时间：" + now.strftime("%Y-%m-%d %H:%M:%S"))
    body.append("- 分支：" + git_branch() + "    基线 commit：" + git_head())
    body.append("- 本环完成：" + (summary or "(未填)"))
    body.append("- 判定结果：" + (verdict or "(未填)"))
    body.append("- 下一步：" + (next_step or "(未填)"))
    body.append("- 遗留风险 / 待人工确认：" + (risks or "无"))
    body.append("")
    path = OUT_DIR / name
    path.write_text("\n".join(body), encoding="utf-8")
    return path


def main(argv=None):
    ap = argparse.ArgumentParser(description="跨环节交接卡")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p_new = sub.add_parser("new", help="写一张新卡")
    p_new.add_argument("--loop", required=True, choices=sorted(LOOPS))
    p_new.add_argument("--summary", default="")
    p_new.add_argument("--verdict", default="")
    p_new.add_argument("--next", dest="next_step", default="")
    p_new.add_argument("--risks", default="")

    sub.add_parser("latest", help="打印最新一张卡")
    sub.add_parser("list", help="列出所有卡")

    args = ap.parse_args(argv)

    if args.cmd == "new":
        path = build(args.loop, args.summary, args.verdict, args.next_step, args.risks)
        print(str(path.relative_to(ROOT)))
        return 0

    if args.cmd == "list":
        for c in cards():
            print(c.name)
        return 0

    if args.cmd == "latest":
        found = cards()
        if not found:
            print("还没有交接卡。用 handoff.py new --loop L1 ... 写第一张。")
            return 0
        print(found[-1].read_text(encoding="utf-8"))
        return 0

    return 1


if __name__ == "__main__":
    sys.exit(main())
