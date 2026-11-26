# -*- coding: utf-8 -*-
"""扫描仓库源码，找出 U+FFFD 损坏字符与「混进来的非中文/非拉丁文字」并报告所在行。

用法（默认扫全仓已入库文本）：

    python tools/scan_bad_chars.py                    # 扫仓库（取 git ls-files）
    python tools/scan_bad_chars.py <目录>              # 扫指定目录（os.walk，供自测）
    python -m unittest tools.test_scan_bad_chars      # 本脚本自己的回归测试

只读，不修改任何文件。命中 ≥ 1 处时**退出码 1**（否则 CI 判不出来 —— 这一条此前缺失：
脚本打印了「共 6 处」却仍然 exit 0，于是它从 2026-11 起一路红着没人看见）。

## 为什么有两条规则

1. **U+FFFD（替换字符）** —— 编码转换失败留下的残骸。它不会让编译或测试失败，
   只会让注释和文案变成「不合法\uFFFD\uFFFD\uFFFD置」这种读不出意思的句子。
2. **韩文 / 日文假名 / 西里尔字母** —— 本项目是中文 + 英文（外加少量箭号、全角标点），
   这些文字块**没有正当用途**。2026-11-25 就有一处在
   `tools/e2e/e2e_review_appeal.py`（「点←这里插了一个韩文字」），
   只查 U+FFFD 抓不到它，所以单独一条规则。

## 为什么用 `git ls-files` 而不是全目录

未入库的**产物**（`docs/reports/assets/` 的截图、`.docx` / `.pdf`）不是仓库内容，
不该让 CI 变红，也不该被「顺手修一下」。所以清单取
`git ls-files --cached --others --exclude-standard`：

- `--cached` —— 已入库的文件（CI 里就是 checkout 出来的那一份）
- `--others --exclude-standard` —— 新增但还未 `git add` 的文件，**但跳过 `.gitignore` 忽略的**

加 `--others` 是因为第一版只取 `--cached`，于是「刚写的新文件还没 add」时本地扫不到，
要等提交后 CI 才红 —— 反馈循环变长，而这类修复本来就该在写的时候顺手做。
`<目录>` 参数保留给自测与一次性排查用。

## 豁免

单行尾部写 `bad-chars-allow <理由>` 即跳过该行 —— 与 `tools/e2e/` 的 allowlist 同一思路：
**显式、逐行、带理由**，不做目录级或模式级豁免（隐式豁免会让检查悄悄失效）。
"""

from __future__ import annotations

import os
import re
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8")

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))

#: 检查的文本后缀。覆盖后端 / 前端 / 脚本 / 文档 / 配置五类；
#: 二进制（.png / .docx / .pdf / .jar）不在此列，它们本来就不该被当文本读
TEXT_SUFFIXES = (
    ".java", ".vue", ".js", ".mjs", ".cjs", ".ts", ".json",
    ".md", ".sql", ".yml", ".yaml", ".scss", ".css", ".html",
    ".py", ".ps1", ".sh", ".txt",
)

#: 构建产物 / 依赖目录：任何层级出现即整棵跳过
SKIP_DIR_NAMES = {".git", "node_modules", "target", "dist", "__pycache__", ".venv", "venv"}

#: 替换字符 —— 编码失败的直接证据
REPLACEMENT_CHAR = "\ufffd"

#: 不该出现在本项目里的文字块：
#:   ① U+1100–11FF 韩文字母  ② U+3130–318F 韩文兼容字母
#:   ③ U+AC00–D7AF 韩文音节  ④ U+3040–30FF 日文假名
#:   ⑤ U+0400–04FF 西里尔字母
FOREIGN_SCRIPT_RE = re.compile(r"[\u1100-\u11ff\u3130-\u318f\uac00-\ud7af\u3040-\u30ff\u0400-\u04ff]")

#: 单行豁免标记（尾部注释即可，例：`# bad-chars-allow 用户粘贴的原样日志`）
ALLOW_MARKER = "bad-chars-allow"


def _iter_repo_files(root: str) -> list[str]:
    """仓库根：git 视角的文件清单（已入库 + 新增未忽略，均遵守 .gitignore）。

    ⚠️ 单用 `--cached` 会漏掉「刚写、还没 `git add`」的新文件 ——
    那样坏字符要等提交后 CI 才被发现。加 `--others --exclude-standard` 补上这一段，
    同时又不会把 `.gitignore` 里的产物拉进来。
    """
    try:
        proc = subprocess.run(
            ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard", "--", "."],
            cwd=root, capture_output=True, text=True,
            encoding="utf-8", errors="replace",
        )
    except OSError:
        proc = None
    if proc is None or proc.returncode != 0:
        # 没有 git（非仓库 / 干净导出）时回落到全目录走查，宁可多扫不可漏扫
        return sorted(_walk_files(root))
    return [os.path.join(root, rel) for rel in proc.stdout.split("\0") if rel]


def _walk_files(root: str) -> list[str]:
    """指定目录：真走文件系统（自测与一次性排查用）。"""
    found = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIR_NAMES]
        for name in filenames:
            found.append(os.path.join(dirpath, name))
    return found


def scan_file(path: str) -> list[tuple[int, str, str]]:
    """扫一个文件，返回 [(行号, 命中片段, 整行)]。

    ⚠️ 用 `errors="replace"` 读：文件本身可能已经是坏编码，
    读失败就扫不到问题 —— 那种「读不了所以跳过」正是要防的静默失效。
    """
    try:
        with open(path, encoding="utf-8", errors="replace") as handle:
            text = handle.read()
    except OSError:
        return []
    hits = []
    for lineno, line in enumerate(text.splitlines(), 1):
        if ALLOW_MARKER in line:
            continue
        if REPLACEMENT_CHAR in line:
            hits.append((lineno, "U+FFFD 替换字符", line.strip()))
            continue
        match = FOREIGN_SCRIPT_RE.search(line)
        if match:
            hits.append((lineno, "非中英文字符 %r" % match.group(0), line.strip()))
    return hits


def scan(paths: list[str]) -> list[str]:
    """扫一批文件，返回已格式化的命中报告行。"""
    reports = []
    for path in paths:
        if not path.endswith(TEXT_SUFFIXES):
            continue
        for lineno, reason, line in scan_file(path):
            reports.append("%s:%d: [%s] %s" % (_display(path), lineno, reason, line))
    return reports


def _display(path: str) -> str:
    """仓库内路径用相对形式（CI 日志里短且稳定），仓库外保留绝对路径。"""
    try:
        rel = os.path.relpath(os.path.abspath(path), ROOT)
    except ValueError:
        return path
    return path if rel.startswith("..") else rel.replace("\\", "/")


def main(argv: list[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    root = os.path.abspath(args[0]) if args else ROOT

    if not os.path.isdir(root):
        print("[错误] 不是目录：%s" % root)
        return 2

    paths = _iter_repo_files(root) if root == ROOT else _walk_files(root)
    reports = scan(paths)

    for report in reports:
        print(report)
    print("--- 共 %d 处（扫描 %d 个文件）---" % (len(reports), len(paths)))

    if reports:
        print("\n修复方式：这些字符是编码转换失败留下的残骸（不是笔误），"
              "请按上下文补回原字或改写整句，不要原样保留。")
        print("确需保留某一行时，行尾加 `%s <理由>`。" % ALLOW_MARKER)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
