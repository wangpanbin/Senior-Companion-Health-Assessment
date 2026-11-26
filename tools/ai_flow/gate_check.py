# -*- coding: utf-8 -*-
"""静态门禁统一入口（本地版，不依赖 MySQL / Redis / 浏览器）。

背景
----
仓库里 CI (.github/workflows/ci.yml) 有三个 Python 静态门禁：
密钥体检、指标字典人读面↔机器面一致性、FAQ 锚点。
本地总闸门 tools/verify_all.ps1 里**没有**这三个 —— 只跑 lint / build / mvn test /
playwright / 覆盖率。结果是「本地全绿、CI 红了」。

本脚本把两侧收拢到同一个入口，并新增一道仓库此前没有的门禁：
错误码 ResultCode.java ↔ docs/api/README.md 错误码总表的**码值双向一致性**
（AGENTS.md §9 矩阵第 2 行要求同步，但此前全靠人记）。

用法
----
    python tools/ai_flow/gate_check.py                 # 全量静态门禁
    python tools/ai_flow/gate_check.py --skip-faq      # 跳过 FAQ 锚点
    python tools/ai_flow/gate_check.py --base HEAD~1   # 文档三项校验只覆盖该范围的改动
    python tools/ai_flow/gate_check.py --strict-text   # 错误码含义文字不一致也算失败

退出码：0 全过 / 1 有失败项。
零依赖（只用标准库）。
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

# --------------------------------------------------------------------------
# 输出编码：强制 UTF-8
# --------------------------------------------------------------------------
# Windows 下 Python 的 stdout 默认跟系统代码页（简中 = GBK / cp936），
# 本脚本与它调起的三个子脚本都会打印 ✅ / ❌ 这类符号，于是：
#
#     UnicodeEncodeError: 'gbk' codec can't encode character '\u2705'
#
# 最坏的后果不是报错本身，而是它**发生在检查全过之后的那行手上**：
# 检查 PASS，然后打印汇总时崩 → 退出码 1 → 调用方（人或 agent）看到的
# 是一个红门禁，会花时间去查一个根本不存在的码值不一致。
# 2026-11-26 实测：`python tools/ai_flow/gate_check.py --only secrets`
# 在默认环境下直接 FAIL，而单跑 check_secrets.py 五条全过。
#
# 所以在这里一次性把三处都钉住：
#   ① 本进程的 stdout / stderr；
#   ② 传给子进程的环境（PYTHONIOENCODING，子进程写到管道时默认也是 cp936）；
#   ③ 子进程自身的输出解码（run_script 里的 encoding="utf-8" 已就位）。
# 调用方不再需要记 PYTHONIOENCODING —— AGENTS.md §0.2 教的命令必须能直接跑。
for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parents[2]
BT = chr(96)
FENCE = BT * 3

RESULT_CODE_JAVA = "backend/src/main/java/org/company/nianglin/common/ResultCode.java"
API_README = "docs/api/README.md"
ROUTES_JS = "frontend/src/router/routes.js"
DESIGN_MD = "docs/design/design.md"

PATH_PREFIXES = (
    "docs/",
    "backend/",
    "frontend/",
    "tools/",
    "ai-service/",
    "plans/",
    "reports/",
)


# --------------------------------------------------------------------------
# 门禁 1-3：直接复用仓库已有的脚本（规则本体只有一份，这里只做编排）
# --------------------------------------------------------------------------

def run_script(rel_path, label):
    cmd = [sys.executable, rel_path]
    # 子进程写的是**管道**，Windows 下管道编码取自 locale（cp936），
    # 于是一个打印 ✅ 的脚本会死在最后一行而不是检查失败。
    # PYTHONIOENCODING 是唯能穿透管道的可靠办法（PYTHONUTF8 也行，但显式编码更准）。
    env = dict(os.environ)
    env["PYTHONIOENCODING"] = "utf-8"
    env.setdefault("PYTHONUTF8", "1")
    proc = subprocess.run(
        cmd,
        cwd=str(ROOT),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        env=env,
    )
    tail = (proc.stdout or "") + (proc.stderr or "")
    lines = [ln.strip() for ln in tail.splitlines() if ln.strip()]
    detail = lines[-1] if lines else "无输出"
    return proc.returncode == 0, detail


# --------------------------------------------------------------------------
# 门禁 4（新增）：错误码双向一致性
# --------------------------------------------------------------------------

JAVA_CODE_RE = re.compile(r"^\s*([A-Z][A-Z0-9_]*)\((\d+),\s*\"")


def read_java_codes():
    path = ROOT / RESULT_CODE_JAVA
    if not path.is_file():
        return {}, "找不到 " + RESULT_CODE_JAVA
    codes = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        m = JAVA_CODE_RE.match(line)
        if m:
            codes[m.group(2)] = m.group(1)
    return codes, ""


def read_doc_codes():
    path = ROOT / API_README
    if not path.is_file():
        return {}, "找不到 " + API_README
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()
    start = None
    for i, ln in enumerate(lines):
        if ln.strip().startswith("## ") and "错误码总表" in ln:
            start = i
            break
    if start is None:
        return {}, "在 " + API_README + " 里找不到「错误码总表」章节"
    codes = {}
    for ln in lines[start + 1:]:
        if ln.strip().startswith("## "):
            break
        m = re.match(r"^\|\s*(\d+)\s*\|", ln)
        if m:
            codes[m.group(1)] = ln
    return codes, ""


def check_error_codes(strict_text):
    java, err = read_java_codes()
    if err:
        return False, err
    doc, err = read_doc_codes()
    if err:
        return False, err

    only_java = sorted(set(java) - set(doc), key=lambda x: int(x))
    only_doc = sorted(set(doc) - set(java), key=lambda x: int(x))
    if only_java or only_doc:
        parts = []
        if only_java:
            parts.append("枚举有文档无：" + ",".join(only_java))
        if only_doc:
            parts.append("文档有枚举无：" + ",".join(only_doc))
        return False, "码值集不一致（" + "；".join(parts) + "）"

    # 含义文字比对：默认只提示，不算失败（历史文案口径本来就略有出入）
    text_diff = []
    for code in sorted(doc, key=lambda x: int(x)):
        cells = [c.strip() for c in doc[code].strip().strip("|").split("|")]
        doc_msg = cells[1] if len(cells) > 1 else ""
        java_name = java.get(code, "")
        if doc_msg and java_name:
            jline = None
            for ln in (ROOT / RESULT_CODE_JAVA).read_text(encoding="utf-8").splitlines():
                if JAVA_CODE_RE.match(ln) and JAVA_CODE_RE.match(ln).group(2) == code:
                    jline = ln
                    break
            if jline:
                jm = re.search(r"\(\d+,\s*\"" + java_name + r"\"", jline)
                jmsg = jm.group(0).split('"', 2)[2].rstrip('"') if jm else ""
                if jmsg and jmsg != doc_msg:
                    text_diff.append(code + "（枚举「" + jmsg + "」/ 文档「" + doc_msg + "」）")
    if text_diff:
        detail = "码值集一致；含义文字有 " + str(len(text_diff)) + " 处不一致：" + "；".join(text_diff[:5])
        if strict_text:
            return False, detail
        return True, detail
    return True, "码值集一致（" + str(len(java)) + " 个），含义文字也一致"


# --------------------------------------------------------------------------
# 门禁 5：改动 Markdown 的三项校验（围栏成对 / 引用路径存在）
# --------------------------------------------------------------------------

def changed_files(base=None):
    """本次改动涉及的 markdown：已跟踪的改动 + 未跟踪的新文件。

    未跟踪文件必须一起算 —— 全新写的 .md 恰恰最容易漏代码围栏。
    """
    args = ["git", "-c", "core.quotepath=false", "diff", "--name-only"]
    if base:
        # 区间写法（A..B）原样传；只给基线时补成 base..HEAD
        args.append(base if ".." in base else base + "..HEAD")
    else:
        args.append("HEAD")
    proc = subprocess.run(
        args, cwd=str(ROOT), capture_output=True, text=True, encoding="utf-8", errors="replace"
    )
    out = [ln.strip().replace("\\", "/") for ln in (proc.stdout or "").splitlines()]
    out = [p for p in out if p]
    if not base:
        proc2 = subprocess.run(
            ["git", "-c", "core.quotepath=false", "ls-files", "--others", "--exclude-standard"],
            cwd=str(ROOT),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        out += [
            ln.strip().replace("\\", "/")
            for ln in (proc2.stdout or "").splitlines()
            if ln.strip()
        ]
    seen = set()
    uniq = []
    for p in out:
        if p not in seen:
            seen.add(p)
            uniq.append(p)
    return uniq


# 只在**根层级**生效的跳过目录名。
#
# ⚠️ `reports` 只能待在这一组里（2026-11-25 修）：
#   本意是跳过根目录的 `reports/`（`.gitignore:98` 的 `/reports/`，Playwright / e2e 产物），
#   但早先用 `any(seg in MD_SKIP_DIRS for seg in parts[:-1])` 做**任意层级**匹配，
#   于是 `docs/reports/` 这个**入库的正式报告目录**（28 份 .md）被一起静默跳过 ——
#   围栏检查与路径检查双双漏检。
#   根层级锚定后：根 `reports/` 照跳（它本来就被 gitignore），`docs/reports/` 回到扫描范围。
MD_SKIP_ROOT_DIRS = (
    "reports",
)

# 任意层级生效的跳过目录名（构建产物与工具缓存，仓库内不应有 .md）
MD_SKIP_ANY_DIRS = (
    "node_modules",
    "target",
    "dist",
    "__pycache__",
    ".venv",
    ".pytest_cache",
    ".idea",
)


def all_md_files():
    """仓库内全部 markdown（排除构建产物与隐藏目录）。

    CI 里没有「本次改动」这回事（checkout 出来工作区是干净的），
    所以 CI 走全量扫描；本地默认只扫改动过的。
    """
    out = []
    for p in ROOT.rglob("*.md"):
        rel = p.relative_to(ROOT).as_posix()
        parts = rel.split("/")
        if any(seg.startswith(".") for seg in parts):
            continue
        if parts[0] in MD_SKIP_ROOT_DIRS:
            continue
        if any(seg in MD_SKIP_ANY_DIRS for seg in parts[:-1]):
            continue
        out.append(rel)
    return sorted(out)


def check_fences(text):
    """返回未闭合的代码围栏数量（成对即 0）。"""
    depth = 0
    for ln in text.splitlines():
        stripped = ln.lstrip()
        if stripped.startswith(FENCE):
            if depth == 0:
                depth = len(stripped) - len(stripped.lstrip(BT))
            else:
                depth = 0
    return depth


# 占位符与区间写法：不是具体路径，不该报「悬空」
PLACEHOLDER_MARKERS = ("–", "—", "~", "{", "}", "YYYY", "NNNN", "...")
# 形如 Xxx / yyy / TODO 的占位文件名（大小写不敏感）
PLACEHOLDER_NAME_RE = re.compile(r"(?:^|[^a-z0-9])(?:x{2,}|todo|tbd)(?:[^a-z0-9]|$)", re.IGNORECASE)
# 路径中间出现纯数字段 = 范围写法（如 iteration-test-report-1/2/3.md 指三份文件）
RANGE_SEGMENT_RE = re.compile(r"^\d+$")
# 历史档案：CHANGELOG 记录的是当时的计划文档，那些文件后来被重命名/合并，
# 悬空是正常现象，不该让 CI 为历史流水账变红。围栏仍要查。
PATH_CHECK_EXEMPT_FILES = ("CHANGELOG.md",)

# 历史档案目录，与上面同一判据：
#   · `docs/plan/archive/` —— 计划存档，含「当时 git rm 了哪些文件」的执行步骤；
#   · `docs/review/`       —— 评审记录，引用被评审那天的代码位置。
# 让快照随文件改名而失效，等于篡改历史；正确做法是**新文档**指向现在的位置。
# 围栏（代码块成对）照旧检查 —— 那跟时间无关。
PATH_CHECK_EXEMPT_DIRS = ("docs/plan/archive/", "docs/review/")


def path_check_exempt(rel):
    """该 .md 是否豁免「悬空路径」判定（只豁免路径，围栏照查）。"""
    return rel in PATH_CHECK_EXEMPT_FILES or rel.startswith(PATH_CHECK_EXEMPT_DIRS)


def git_ignored_paths(paths):
    """问 git：这些路径里哪些被 .gitignore 忽略。

    ⚠️ **不要**在脚本里再抄一份忽略清单 —— 抄两份必然漂移，
    而漂移的表现恰恰是「本地绿、CI 红」：本地有那个被忽略的文件
    （如 `ai-service/.env`），CI 的全新检出里没有，于是同一段文档
    一边 PASS 一边 FAIL。

    规则本体只有一处，就是 `.gitignore`，由 git 自己回答。

    被忽略的文件（本地 `.env`、构建产物、测试产出）在 CI 上本来就不存在，
    但文档引用它们是**合法**的 —— 所以它们不算「悬空路径」。
    """
    if not paths:
        return set()
    try:
        # ⚠️ 必须传**字节**：`text=True` 时 Python 在 Windows 会把 \n 翻成 \r\n，
        # 而 git 的 --stdin 把行尾的 \r 当成路径的一部分 —— 于是路径匹配不到，
        # 规则被静默跳过（Linux 上不会复现，属典型「本地绿、CI 绿但规则失效」）。
        payload = ("\n".join(paths) + "\n").encode("utf-8")
        proc = subprocess.run(
            ["git", "check-ignore", "--stdin", "--no-index"],
            cwd=str(ROOT),
            input=payload,
            capture_output=True,
        )
    except OSError:
        return set()
    out = (proc.stdout or b"").decode("utf-8", errors="replace")
    return {
        ln.strip().replace("\\", "/")
        for ln in out.splitlines()
        if ln.strip()
    }


def check_md_paths(text):
    """找出文档里引用但不存在的仓库内路径。"""
    span_re = re.compile(BT + r"([^" + BT + r"\n]+)" + BT)
    candidates = []
    for m in span_re.finditer(text):
        token = m.group(1).strip()
        # 只取第一个空白分隔片段（后面通常是散文说明）
        token = token.split()[0] if token.split() else ""
        token = token.rstrip("，。、；：,.;:。")
        # 行号后缀：index.js:70 / auth.js:13,77 / spec.md:270-272 -> 去掉行号
        token = re.sub(r":[\d,\-\u2013]+$", "", token)
        # 锚点后缀：README.md#L360-L376 -> README.md
        token = token.split("#")[0]
        if not token.startswith(PATH_PREFIXES):
            continue
        if any(ch in token for ch in "*?<>|"):
            continue
        if any(mark in token for mark in PLACEHOLDER_MARKERS):
            continue
        if PLACEHOLDER_NAME_RE.search(token):
            continue
        if token.endswith("/"):
            continue
        # 必须像具体文件名（有扩展名），排除「docs/api/01」这类片段
        last = token.rsplit("/", 1)[-1]
        if "." not in last:
            continue
        # 中间段是纯数字 = 范围写法（report-1/2/3.md 指三份文件）
        segments = token.split("/")
        if any(RANGE_SEGMENT_RE.match(seg) for seg in segments[1:-1]):
            continue
        candidates.append(token)
    # 磁盘上不存在的候选才可能是「悬空路径」——
    # 再用 git 排除「被 .gitignore 忽略」的：它们在 CI 的全新检出里同样不存在，
    # 但文档引用它们是合法的（本地 .env / 构建产物 / 测试产出）。
    not_exist = [t for t in dict.fromkeys(candidates) if not (ROOT / t).exists()]
    ignored = git_ignored_paths(not_exist)
    return [t for t in not_exist if t not in ignored]


def check_markdown(base, all_md=False):
    files = all_md_files() if all_md else [p for p in changed_files(base) if p.endswith(".md")]
    if not files:
        return True, "本次改动没有 .md 文件，跳过", []
    bad_fence = []
    bad_path = []
    for rel in files:
        path = ROOT / rel
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        if check_fences(text):
            bad_fence.append(rel)
        if path_check_exempt(rel):
            continue
        for token in check_md_paths(text):
            bad_path.append(rel + " -> " + token)
    detail = "检查 " + str(len(files)) + " 个 .md：围栏 " + str(len(bad_fence)) + " 处不闭合，悬空路径 " + str(len(bad_path)) + " 处"
    if bad_fence or bad_path:
        parts = []
        if bad_fence:
            parts.append("围栏不闭合：" + ",".join(bad_fence))
        if bad_path:
            parts.append("悬空路径（" + str(len(bad_path)) + " 处）：" + "；".join(bad_path[:40]))
        return False, detail + " | " + " | ".join(parts), bad_fence + bad_path
    return True, detail, []


# --------------------------------------------------------------------------
# 门禁 5（新增）：页面编号双向一致
# --------------------------------------------------------------------------
# routes.js 的 meta.code 注释写着「页面编号，如 M-04（与 docs/design/design.md
# §1.2/1.3 对齐）」—— 但这个对齐关系此前**从未被检查过**：
# 2026-11-26 实测 design.md 只有 22 条、routes.js 有 52 条，28 个编号只存在于路由一侧。
# 新增页面的人（包括 AI）只能手工扫表挑一个没人用的号，然后自己记得往 design.md 补一行。
#
# 形状与上面的「错误码双向一致」完全一样：两边各跑一个正则取集合，做双向差集。
# 为什么值得单独一门禁而不是靠评审：挑号是机械动作，而「已经有人用了」这件事
# 只能靠全局视图知道 —— 单看一个文件永远看不出来。

ROUTE_CODE_RE = re.compile(r"code:\s*'(M-\d+|W-\d+)'")
# design.md 里部分行把编号整格加粗（`| **M-04** |`），因此正则必须先吃掉可选的 `**`。
# 不处理这个会让「已经写了的 11 行」被误报成缺失，门禁一上来就是假红。
DESIGN_CODE_RE = re.compile(r"^\|\s*(?:\*\*)?(M-\d+|W-\d+)(?:\*\*)?\s*\|", re.M)

#: design.md 允许出现、但**不**对应独立路由的编号（每条都带理由）。
#:
#: 为什么需要这份白名单而不是把三行删掉：design.md §1.2/1.3 是**设计稿的页面清单**，
#: 它描述的是「要画哪些界面」，与「路由表里有哪些 path」本来就不同构 ——
#: 一个路由可以有多种形态（老人模式），一个编号可以是一组全局组件。
#: 把差异写成带理由的白名单，比让两侧集合强制相等诚实，也比静默跳过安全：
#: 新增的孤立编号会真实报错，而非滲在白名单里。
DESIGN_ONLY_CODES = {
    # 与 M-04 共用 /elder/home，只差全局字号与对比度；不另建路由。
    "M-05": "老人端首页的老人模式形态，与 M-04 共用同一条路由",
    # 用药详情与确认服药是 /family/medication 页内的状态，不是独立页面。
    "M-13": "用药计划详情 / 确认服药，是 M-12 页内的状态而非独立路由",
    # 「空状态/加载/错误」是一组共享组件的设计约定，全站复用，没有自己的 path。
    "M-23": "空状态 / 加载 / 错误三态组件集合，全站复用，无独立路由",
}


def read_route_codes():
    path = ROOT / ROUTES_JS
    if not path.is_file():
        return set(), "找不到 " + ROUTES_JS
    return set(ROUTE_CODE_RE.findall(path.read_text(encoding="utf-8"))), ""


def read_design_codes():
    path = ROOT / DESIGN_MD
    if not path.is_file():
        return set(), "找不到 " + DESIGN_MD
    return set(DESIGN_CODE_RE.findall(path.read_text(encoding="utf-8"))), ""


def check_page_codes():
    route_codes, err = read_route_codes()
    if err:
        return False, err, []
    design_codes, err = read_design_codes()
    if err:
        return False, err, []

    only_route = sorted(route_codes - design_codes, key=_code_sort_key)
    orphan_design = sorted(design_codes - route_codes - set(DESIGN_ONLY_CODES), key=_code_sort_key)
    detail = "路由 %d 个编号 / 设计稿 %d 个编号（另有 %d 个设计稿专属）" % (
        len(route_codes), len(design_codes), len(design_codes & set(DESIGN_ONLY_CODES)))
    if only_route or orphan_design:
        parts = []
        if only_route:
            parts.append("已建路由但 design.md 无记录（%d）：%s —— 新增页面时补一行页面表"
                         % (len(only_route), ",".join(only_route)))
        if orphan_design:
            parts.append("design.md 有但既无路由也不在白名单（%d）：%s —— 要么补路由，要么加进 DESIGN_ONLY_CODES 并写理由"
                         % (len(orphan_design), ",".join(orphan_design)))
        return False, detail + " | " + "；".join(parts), only_route + orphan_design
    return True, detail + "，覆盖完整", []


def _code_sort_key(code):
    """按「前缀 M/W + 数字」排序，让报错顺序与 design.md 表格顺序一致（M-02 在 M-10 之前）。"""
    kind, num = code.split("-")
    return (0 if kind == "M" else 1, int(num))


# --------------------------------------------------------------------------

GATE_IDS = ("secrets", "metric-dict", "faq", "error-codes", "page-codes", "md")


def main(argv=None):
    ap = argparse.ArgumentParser(description="静态门禁统一入口")
    ap.add_argument("--base", default=None, help="校验范围：某个 ref..HEAD；默认工作区 vs HEAD")
    ap.add_argument("--skip-faq", action="store_true", help="跳过 FAQ 锚点检查（--skip 的别名）")
    ap.add_argument("--strict-text", action="store_true", help="错误码含义文字不一致也算失败")
    ap.add_argument("--all-md", action="store_true", help="Markdown 校验覆盖仓库全部 .md（CI 用）")
    ap.add_argument(
        "--only",
        default="",
        help="只跑这些门禁，逗号分隔。可选：" + ",".join(GATE_IDS) + "（CI 按需取用，避免与既有 job 重复）",
    )
    ap.add_argument("--skip", default="", help="跳过这些门禁，逗号分隔")
    args = ap.parse_args(argv)

    only = [x.strip() for x in args.only.split(",") if x.strip()]
    skip = [x.strip() for x in args.skip.split(",") if x.strip()]
    if args.skip_faq and "faq" not in skip:
        skip.append("faq")
    for gid in only + skip:
        if gid not in GATE_IDS:
            ap.error("未知门禁 id：" + gid + "（可选：" + ",".join(GATE_IDS) + "）")

    def enabled(gid):
        if only:
            return gid in only
        return gid not in skip

    results = []

    def add(name, ok, detail):
        results.append((name, "PASS" if ok else "FAIL", detail))

    if enabled("secrets"):
        ok, detail = run_script("tools/check_secrets.py", "secrets")
        add("密钥体检", ok, detail)
    if enabled("metric-dict"):
        ok, detail = run_script("tools/check_metric_dict_parity.py", "metric")
        add("指标字典 parity", ok, detail)
    if enabled("faq"):
        ok, detail = run_script("tools/check_faq_anchors.py", "faq")
        add("FAQ 锚点", ok, detail)
    if enabled("error-codes"):
        ok, detail = check_error_codes(args.strict_text)
        add("错误码双向一致", ok, detail)
    if enabled("page-codes"):
        ok, detail, _ = check_page_codes()
        add("页面编号双向一致", ok, detail)
    if enabled("md"):
        ok, detail, _ = check_markdown(args.base, args.all_md)
        add("Markdown 三项校验", ok, detail)

    print("=" * 74)
    print("静态门禁汇总（tools/ai_flow/gate_check.py）")
    if only:
        print("本次只跑：" + ",".join(only))
    elif skip:
        print("本次跳过：" + ",".join(skip))
    print("=" * 74)
    for name, res, detail in results:
        print("{:<16} {:<5} {}".format(name, res, detail))
    failed = [r for r in results if r[1] == "FAIL"]
    print("-" * 74)
    if failed:
        print("静态门禁：FAIL（" + str(len(failed)) + " 项未过）")
        return 1
    print("静态门禁：PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
