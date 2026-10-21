# -*- coding: utf-8 -*-
"""检查并统一仓库工作区的行尾（CRLF）。

背景：本机 git 的 core.autocrlf=true，工作区是 CRLF。
用 Write 工具新建的文件默认写成 LF，会让 `git diff` 刷
「LF will be replaced by CRLF」之类的警告。

用法：
    python tools/fix_line_endings.py --check   # 只检查，列出不一致的文件
    python tools/fix_line_endings.py --fix     # 把 LF-only 文件转成 CRLF

只处理**工作区里已修改或新增**的文件（git status 有改动的），
不碰未改动的文件。

⚠️ 这是**本地开发辅助脚本，不是 CI 门禁** —— 不要把它加进
`.github/workflows/ci.yml`：行尾是编辑器偏好，不是代码质量，
把它变成构建条件只会给后来人添麻烦。
"""
import argparse
import io
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def changed_files():
    """git status --porcelain 里的改动文件（排除删除与目录）。"""
    out = subprocess.run(
        ["git", "status", "--porcelain"],
        cwd=str(ROOT), capture_output=True, text=True, encoding="utf-8", errors="replace",
    ).stdout
    paths = []
    for line in out.splitlines():
        if not line.strip():
            continue
        # porcelain 格式：XY <path>；重命名时是 "old -> new"
        raw = line[3:].strip()
        if " -> " in raw:
            raw = raw.split(" -> ", 1)[1]
        raw = raw.strip('"')
        p = ROOT / raw
        if p.is_file():
            paths.append(p)
    return paths


def classify(path):
    """返回 (kind, detail)：kind ∈ {crlf, lf, mixed, empty, binary}。"""
    data = path.read_bytes()
    if b"\x00" in data[:8192]:
        return "binary", ""
    if not data:
        return "empty", ""
    crlf = data.count(b"\r\n")
    lf = data.count(b"\n") - crlf
    if crlf and lf:
        return "mixed", "CRLF={} LF={}".format(crlf, lf)
    if crlf:
        return "crlf", "CRLF={}".format(crlf)
    return "lf", "LF={}".format(lf)


def to_crlf(path):
    data = path.read_bytes()
    normalized = data.replace(b"\r\n", b"\n").replace(b"\n", b"\r\n")
    if normalized != data:
        path.write_bytes(normalized)
        return True
    return False


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--fix", action="store_true")
    args = parser.parse_args()
    if not (args.check or args.fix):
        parser.error("必须指定 --check 或 --fix")

    targets = changed_files()
    if not targets:
        print("工作区没有改动文件")
        return 0

    problems = []
    for p in targets:
        kind, detail = classify(p)
        if kind in ("lf", "mixed"):
            problems.append((p, kind, detail))

    if args.check:
        if not problems:
            print("[OK] {} 个改动文件的行尾一致（CRLF）".format(len(targets)))
            return 0
        print("以下文件行尾不是纯 CRLF：")
        for p, kind, detail in problems:
            print("  [{}] {} ({})".format(kind, p.relative_to(ROOT).as_posix(), detail))
        return 1

    fixed = []
    for p, kind, _ in problems:
        if to_crlf(p):
            fixed.append(p)
    for p in fixed:
        print("已转 CRLF: {}".format(p.relative_to(ROOT).as_posix()))
    print("共处理 {} 个文件（检查了 {} 个改动文件）".format(len(fixed), len(targets)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
