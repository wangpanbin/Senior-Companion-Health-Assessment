# -*- coding: utf-8 -*-
"""改动影响面 -> 文档同步对账表（契约级）。

把 docs/agents/doc-sync.md 的联动矩阵变成可执行的表：输入一组改动文件，
输出「这次改动必须联动哪些文档 / 哪些已经覆盖 / 哪些真欠账 / 哪些要人拍板」。

为什么是「契约级」而不是「路径级」
--------------------------------
只比「目标文档有没有出现在本次改动里」会误报：本仓库执行**接口先行**
（docs/agents/api-first.md），接口文档常常在更早的提交里就写好了 ——
那时按提交审计会喊「欠账」，其实文档早已覆盖。

所以本脚本直接解析被改 Controller / 前端封装里的**接口路径**，
去目标文档里查这条路径出现过没有：
  · 全部出现 -> [已覆盖]
  · 有路径没出现 -> [真欠账]，并点名缺哪几条
  · 解析不出来 -> [抽查]，不当欠账判（宁可漏报，不可误报）

用法
----
    python tools/ai_flow/impact_map.py                  # 工作区（含未跟踪）vs HEAD
    python tools/ai_flow/impact_map.py --base HEAD~1    # HEAD~1..HEAD
    python tools/ai_flow/impact_map.py --base 'A~1..A'  # 审计单个提交
    python tools/ai_flow/impact_map.py --json

退出码
------
    0 = 没有真欠账
    1 = 存在真欠账（接口路径未被文档覆盖）
    2 = 存在需人工确认项（未登记的控制器 / 生成物等），优先于 1

零依赖（只用标准库）。规则依据见 docs/agents/doc-sync.md。
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MATRIX = "docs/agents/doc-sync.md"

# --------------------------------------------------------------------------
# 映射表：全部对照真实文件树核对过（backend/.../controller/、frontend/src/api/、
# docs/api/）。新增控制器 / 新增前端封装必须在此登记，否则会被列为「需人工确认」。
# --------------------------------------------------------------------------

CONTROLLER_API_DOC = {
    "AuthController": "01-auth-user.md",
    "UserController": "01-auth-user.md",
    "ElderController": "02-elder-family.md",
    "ElderHealthController": "12-elder-health.md",
    "FamilyHealthController": "12-elder-health.md",
    "OrderController": "03-order.md",
    "ExecutionController": "04-companion-execution.md",
    "MedicationController": "05-medication.md",
    "ReviewController": "06-review-complaint.md",
    "ComplaintController": "06-review-complaint.md",
    "MessageController": "07-message.md",
    "MessageSseController": "07-message.md",
    "ChatController": "11-order-chat.md",
    "AdminChatController": "11-order-chat.md",
    "AdminController": "08-admin.md",
    "MetricDictionaryController": "08-admin.md",
    "OperationParamController": "08-admin.md",
    "OperationsController": "08-admin.md",
    "StatisticsController": "09-statistics-export.md",
    "CompanionStatisticsController": "09-statistics-export.md",
    "SettlementExportController": "09-statistics-export.md",
    "ExamPrepController": "14-exam-prep.md",
    "FileController": "10-file-upload.md",
    "HealthController": "12-elder-health.md",
    "AssistantController": "13-assistant.md",
}

DOC_FRONTEND_API = {
    "01-auth-user.md": ["frontend/src/api/auth.js", "frontend/src/api/user.js"],
    "02-elder-family.md": ["frontend/src/api/user.js"],
    "03-order.md": ["frontend/src/api/order.js"],
    "04-companion-execution.md": ["frontend/src/api/execution.js"],
    "05-medication.md": ["frontend/src/api/medication.js"],
    "06-review-complaint.md": ["frontend/src/api/review.js"],
    "07-message.md": ["frontend/src/api/message.js"],
    "08-admin.md": ["frontend/src/api/admin.js"],
    "09-statistics-export.md": ["frontend/src/api/statistics.js"],
    "10-file-upload.md": ["frontend/src/api/file.js"],
    "11-order-chat.md": ["frontend/src/api/chat.js"],
    "12-elder-health.md": [
        "frontend/src/api/elderHealth.js",
        "frontend/src/api/health.js",
        "frontend/src/api/vitalSign.js",
    ],
    "13-assistant.md": ["frontend/src/api/assistant.js"],
    "14-exam-prep.md": ["frontend/src/api/examPrep.js"],
}

FRONTEND_API_DOCS = {}
for _d, _fs in DOC_FRONTEND_API.items():
    for _f in _fs:
        FRONTEND_API_DOCS.setdefault(_f, [])
        if _d not in FRONTEND_API_DOCS[_f]:
            FRONTEND_API_DOCS[_f].append(_d)

# 前端统一出口（barrel），不是模块封装，不参与契约核对
FRONTEND_NON_MODULE = {"frontend/src/api/index.js"}

ORDER_STATUS_TEST = "backend/src/test/java/org/company/nianglin/constant/OrderStatusTest.java"
MASK_UTIL_TEST = "backend/src/test/java/org/company/nianglin/util/MaskUtilTest.java"
GLOBAL_EXC_TEST = (
    "backend/src/test/java/org/company/nianglin/exception/GlobalExceptionHandlerClientErrorTest.java"
)
DB_DOC_GENERATOR = "backend/sql/tools/gen_db_docs.py"
COMPLIANCE_WORDS = "backend/src/main/resources/compliance/medical-advice-words.txt"

# 状态：path = 目标路径必须在改动集里
#       contract = 用接口路径做契约覆盖核对
#       manual = 语义类，路径判定不了，需人扫一眼
STATE_PATH = "path"
STATE_CONTRACT = "contract"
STATE_MANUAL = "manual"

NOISE_SUFFIXES = (".log", ".pyc", ".pid", ".tmp", ".swp", ".bak")
NOISE_DIRS = (
    "logs",
    "__pycache__",
    ".venv",
    "venv",
    "node_modules",
    "target",
    "dist",
    ".pytest_cache",
    ".idea",
)

# ---- 接口路径解析 ----
CLASS_MAPPING_RE = re.compile(r'@RequestMapping\s*\(\s*(?:value\s*=\s*)?"([^"]*)"')
METHOD_MAPPING_RE = re.compile(
    r'@(Get|Post|Put|Delete|Patch)Mapping\s*(?:\(\s*(?:value\s*=\s*)?"([^"]*)"[^)]*\))?'
)
_Q = chr(39)
_DQ = chr(34)
_BT = chr(96)
FRONTEND_URL_RE = re.compile(
    r"url\s*:\s*[" + _Q + _DQ + _BT + "]([^" + _Q + _DQ + _BT + "]+)[" + _Q + _DQ + _BT + "]"
)
DOC_PATH_RE = re.compile(r"/api/[A-Za-z0-9_\-/{}.*]+")
VAR_RE = re.compile(r"\{[^}]*\}")
TMPL_VAR_RE = re.compile(r"\$\{[^}]*\}")


def is_noise(p):
    """日志 / 缓存 / 构建产物不构成改动面。"""
    parts = p.split("/")
    base = parts[-1]
    if base.endswith(NOISE_SUFFIXES):
        return True
    for seg in parts[:-1]:
        if seg in NOISE_DIRS:
            return True
    if base.startswith("logs") and base.endswith(".txt"):
        return True
    return False


def is_ai_source(p):
    """ai-service 下真正算「改动」的源码 / 配置（排除日志与缓存）。"""
    if not p.startswith("ai-service/"):
        return False
    rest = p[len("ai-service/"):]
    first = rest.split("/")[0]
    if first.startswith("logs") or first in NOISE_DIRS:
        return False
    if p.endswith((".log", ".pyc", ".db", ".sqlite", ".jsonl")):
        return False
    if p == "ai-service/knowledge/faq.json":
        return False  # 归 R10，不重复报
    if first in ("app", "tests", "knowledge"):
        return True
    if first == "requirements.txt":
        return True
    if "/" not in rest and p.endswith(".py"):
        return True
    return False


def normalize_path(p):
    p = VAR_RE.sub("*", p)
    p = TMPL_VAR_RE.sub("*", p)
    p = re.sub(r"/+", "/", p)
    if len(p) > 1:
        p = p.rstrip("/")
    return p


def parse_controller_endpoints(rel):
    """返回 [(verb, full_path)]；解析不到基路径时返回 None（不可判定）。"""
    path = ROOT / rel
    if not path.is_file():
        return None
    text = path.read_text(encoding="utf-8", errors="replace")
    m = CLASS_MAPPING_RE.search(text)
    if not m:
        return None
    base = m.group(1)
    out = []
    for line in text.splitlines():
        mm = METHOD_MAPPING_RE.search(line)
        if not mm:
            continue
        verb = mm.group(1).upper()
        sub = (mm.group(2) or "").strip()
        full = base.rstrip("/") + "/" + sub.lstrip("/") if sub else base
        out.append((verb, full))
    return out


def parse_frontend_paths(rel):
    """从前端封装里抠出接口路径（baseURL 为 /api）。"""
    path = ROOT / rel
    if not path.is_file():
        return None
    text = path.read_text(encoding="utf-8", errors="replace")
    out = []
    for m in FRONTEND_URL_RE.finditer(text):
        raw = m.group(1).strip()
        if not raw.startswith("/"):
            continue
        out.append("/api" + raw)
    return out or None


def doc_paths(rel):
    """目标文档里出现过的接口路径集合（已归一化）。"""
    path = ROOT / rel
    if not path.is_file():
        return None
    text = path.read_text(encoding="utf-8", errors="replace")
    found = set()
    for m in DOC_PATH_RE.finditer(text):
        t = m.group(0).rstrip(".")
        if t:
            found.add(normalize_path(t))
    return found


def verify_contract(source_rel, doc_rel, kind):
    """契约覆盖核对。

    返回 (status, detail)：
        status = 'covered' 全覆盖 / 'missing' 有缺口 / 'unknown' 判不了
    """
    if kind == "controller":
        eps = parse_controller_endpoints(source_rel)
        if eps is None:
            return "unknown", "解析不出 Controller 基路径，未判定"
        pairs = [(verb, p) for verb, p in eps]
    else:
        eps = parse_frontend_paths(source_rel)
        if eps is None:
            return "unknown", "前端封装里读不到 url 字段，未判定"
        pairs = [("", p) for p in eps]

    dpaths = doc_paths(doc_rel)
    if dpaths is None:
        return "missing", "目标文档不存在"

    missing = []
    for verb, p in pairs:
        if normalize_path(p) not in dpaths:
            missing.append((verb + " " if verb else "") + p)
    if not pairs:
        return "unknown", "没解析出接口路径，未判定"
    if missing:
        return "missing", "缺 " + str(len(missing)) + " 条：" + "；".join(missing[:6])
    return "covered", "已覆盖 " + str(len(pairs)) + " 条接口路径"


def git(*args):
    # core.quotepath=false：否则中文文件名会被 git 转义成八进制串，路径判定全废
    proc = subprocess.run(
        ["git", "-c", "core.quotepath=false", *args],
        cwd=str(ROOT),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return proc.stdout or ""


def collect_changed(base=None):
    """返回改动文件列表（去重保序）。"""
    raw = []
    if base and ".." in base:
        raw += git("diff", "--name-only", base).splitlines()
    elif base:
        raw += git("diff", "--name-only", base + "..HEAD").splitlines()
    else:
        raw += git("diff", "--name-only", "HEAD").splitlines()
        raw += git("ls-files", "--others", "--exclude-standard").splitlines()
    seen = set()
    out = []
    for item in raw:
        p = item.strip().replace("\\", "/")
        if p and p not in seen:
            seen.add(p)
            out.append(p)
    return out


def known_api_docs():
    d = ROOT / "docs" / "api"
    if not d.is_dir():
        return set()
    return {p.name for p in d.glob("*.md")}


def classify(changed):
    """把改动文件映射成规则命中 + 联动目标 + 需人工确认项。"""
    rules = {}
    unknown = []
    api_docs = known_api_docs()

    def get(rid, name, matrix):
        if rid not in rules:
            rules[rid] = {
                "id": rid,
                "name": name,
                "matrix": matrix,
                "hits": [],
                "targets": [],
            }
        return rules[rid]

    def add(rule, path, desc, state=STATE_PATH, source=None, kind=None):
        for t in rule["targets"]:
            if t["path"] == path and t["desc"] == desc:
                return
        item = {"path": path, "desc": desc, "state": state}
        if source:
            item["source"] = source
            item["kind"] = kind
            status, detail = verify_contract(source, path, kind)
            item["status"] = status
            item["detail"] = detail
        rule["targets"].append(item)

    for raw in changed:
        p = raw.replace("\\", "/")
        if is_noise(p):
            continue
        base = Path(p).name
        stem = base[:-5] if base.endswith(".java") else Path(base).stem

        # R1 接口（Controller）改动
        if (
            p.startswith("backend/src/main/java/")
            and "/controller/" in p
            and base.endswith("Controller.java")
        ):
            r = get("R1", "接口（Controller）改动", MATRIX + " 第 1 行")
            r["hits"].append(p)
            doc = CONTROLLER_API_DOC.get(stem)
            if doc is None:
                unknown.append(
                    f"{p} —— 未登记的 Controller（{stem}）：需人工确认对应 docs/api/*.md，"
                    f"并把 {stem} 登记进 impact_map.py 的 CONTROLLER_API_DOC"
                )
                continue
            add(
                r,
                "docs/api/" + doc,
                "接口文档是否已覆盖本次接口路径",
                STATE_CONTRACT,
                source=p,
                kind="controller",
            )
            add(r, p, "Knife4j @Operation / @Schema 注解是否跟上", STATE_MANUAL)
            for fe in DOC_FRONTEND_API.get(doc, []):
                add(r, fe, "前端接口封装（字段有变时）")

        # R1b 前端接口封装改动
        elif p.startswith("frontend/src/api/") and p.endswith(".js"):
            if p in FRONTEND_NON_MODULE:
                continue
            r = get("R1b", "前端接口封装改动", MATRIX + " 第 1 行")
            r["hits"].append(p)
            docs = FRONTEND_API_DOCS.get(p)
            if not docs:
                unknown.append(
                    f"{p} —— 未登记的前端封装文件：需人工确认对应 docs/api/*.md，"
                    f"并把 {p} 登记进 impact_map.py 的 DOC_FRONTEND_API"
                )
                continue
            for doc in docs:
                add(
                    r,
                    "docs/api/" + doc,
                    "接口文档是否已覆盖前端封装的路径",
                    STATE_CONTRACT,
                    source=p,
                    kind="frontend",
                )

        # R2 错误码
        elif p.endswith("common/ResultCode.java"):
            r = get("R2", "错误码改动", MATRIX + " 第 2 行")
            r["hits"].append(p)
            add(r, "docs/api/README.md", "错误码总表（码值 + 含义）")
            add(
                r,
                "docs/api/README.md",
                "校验：python tools/ai_flow/gate_check.py",
                STATE_MANUAL,
            )

        # R3 状态机
        elif p.endswith("constant/OrderStatus.java"):
            r = get("R3", "订单状态机流转改动", MATRIX + " 第 3 行")
            r["hits"].append(p)
            add(r, ORDER_STATUS_TEST, "状态机单测（新增流转必须有对应用例）")
            add(r, "docs/api/03-order.md", "状态机章节")

        # R4 依赖
        elif p in ("backend/pom.xml", "frontend/package.json"):
            r = get("R4", "依赖改动", MATRIX + " 第 4 行")
            r["hits"].append(p)
            add(r, "README.md", "技术栈表")

        # R5 路由 / 菜单
        elif p.endswith("router/routes.js"):
            r = get("R5", "路由 / 菜单改动", MATRIX + " 第 5 行")
            r["hits"].append(p)
            add(
                r,
                "frontend/src/layouts/BasicLayout.vue",
                "老人模式下的菜单隐藏行为",
                STATE_MANUAL,
            )

        # R6 样式令牌
        elif p.startswith("frontend/src/styles/") and p.endswith(".scss"):
            r = get("R6", "样式令牌改动", MATRIX + " 第 6 行")
            r["hits"].append(p)
            for other in (
                "frontend/src/styles/variables.scss",
                "frontend/src/styles/index.scss",
                "frontend/src/styles/elderly.scss",
            ):
                if other != p:
                    add(r, other, "同一令牌三处同步（编译期 / 运行期 / 老人模式覆盖）")

        # R7 数据库表
        elif p.startswith("backend/sql/") and p.endswith(".sql"):
            r = get("R7", "数据库表 / 迁移脚本改动", MATRIX + " 第 7 行")
            r["hits"].append(p)
            add(r, "docs/api/", "对应模块接口文档的数据模型小节（按表名判断）", STATE_MANUAL)
            add(
                r,
                "backend/src/main/java/org/company/nianglin/common/ResultCode.java",
                "如有新业务码值需同步",
                STATE_MANUAL,
            )

        # R8 合规（脱敏 / 加密 / 日志）
        elif p.endswith("util/MaskUtil.java"):
            r = get("R8", "脱敏 / 合规改动", MATRIX + " 第 8 行")
            r["hits"].append(p)
            add(r, MASK_UTIL_TEST, "脱敏单测")
            add(r, "README.md", "合规约束章节", STATE_MANUAL)
        elif p.endswith("exception/GlobalExceptionHandler.java"):
            r = get("R8", "脱敏 / 合规改动", MATRIX + " 第 8 行")
            r["hits"].append(p)
            add(r, GLOBAL_EXC_TEST, "全局异常处理单测")

        # R9 ai-service
        elif is_ai_source(p):
            r = get("R9", "AI 助手服务改动", MATRIX + " 第 9 行")
            r["hits"].append(p)
            add(r, "ai-service/requirements.txt", "依赖清单（越出已批准清单需重新报批）")
            add(r, "AGENTS.md", "§0 技术栈表")
            add(r, "docs/agents/backend-conventions.md", "§2.11 助手服务细则")
            add(r, "docs/api/13-assistant.md", "助手接口文档")
            add(r, "docs/spec/V3.7-assistant-spec.md", "助手服务规格")

        # R10 FAQ 知识库
        if p == "ai-service/knowledge/faq.json":
            r = get("R10", "FAQ 知识库改动", MATRIX + " 第 10 行")
            r["hits"].append(p)
            add(
                r,
                "ai-service/knowledge/faq.json",
                "逐条核对 source + anchor 指向真实锚点；校验：python tools/check_faq_anchors.py",
                STATE_MANUAL,
            )

        # R11 共享合规词表
        if p == COMPLIANCE_WORDS:
            r = get("R11", "共享合规词表改动", MATRIX + " 第 11 行")
            r["hits"].append(p)
            add(r, COMPLIANCE_WORDS, "Java 与 Python 两侧快照必须逐字相同", STATE_MANUAL)

        # R12 docs/db 生成物
        if p.startswith("docs/db/"):
            r = get("R12", "数据库文档改动", MATRIX + " 第 12 行")
            r["hits"].append(p)
            unknown.append(
                f"{p} —— docs/db/ 下是生成物：直接改会被下次 render 冲掉，"
                f"请改生成器 {DB_DOC_GENERATOR} 后重跑"
            )
            add(r, DB_DOC_GENERATOR, "生成器（改动源，不是生成物）")

        # R13 前端交互流程
        if p.startswith("frontend/src/views/"):
            r = get("R13", "前端交互流程改动", MATRIX + " 第 13 行")
            r["hits"].append(p)
            add(
                r,
                "docs/agents/interaction-checklist.md",
                "交互自查；清单未收录的新陷阱要补进清单",
                STATE_MANUAL,
            )

        # R14 文档本身
        if p.endswith(".md"):
            r = get("R14", "Markdown 文档改动", "AGENTS.md §11 验收清单")
            r["hits"].append(p)
            add(r, p, "文档本体")
            add(r, p, "三项校验：代码围栏成对 / 中文句尾全角 / 引用路径真实存在", STATE_MANUAL)

        # R15 新接口文档未登记
        if p.startswith("docs/api/") and base not in api_docs:
            unknown.append(f"{p} —— docs/api/ 下出现了未知文件，需人工确认编号与归属")

    return rules, unknown


def render(rules, unknown, changed, base):
    changed_set = set(changed)
    out = []
    out.append("=" * 74)
    out.append("改动影响面 -> 文档同步对账表（契约级）")
    out.append("基线：" + (base if base else "HEAD（工作区 vs HEAD）"))
    out.append("改动文件：" + str(len(changed)) + " 个    真源：" + MATRIX)
    out.append("=" * 74)
    if changed:
        out.append("")
        out.append("改动清单：")
        for c in changed:
            out.append("  - " + c)

    debt = []
    review = []
    covered = 0
    if rules:
        out.append("")
        out.append("-" * 74)
        out.append("联动矩阵命中")
        out.append("-" * 74)
    for rid in sorted(rules):
        r = rules[rid]
        out.append("")
        out.append("[" + r["id"] + "] " + r["name"] + "    依据 " + r["matrix"])
        for h in r["hits"]:
            out.append("    触发：" + h)
        for t in r["targets"]:
            state = t["state"]
            if state == STATE_CONTRACT:
                status = t.get("status")
                detail = t.get("detail", "")
                if status == "covered":
                    out.append("    [已覆盖] " + t["path"] + "  -- " + t["desc"] + "（" + detail + "）")
                    covered += 1
                elif status == "missing":
                    out.append("    [真欠账] " + t["path"] + "  -- " + t["desc"] + "：" + detail)
                    debt.append("[" + r["id"] + "] " + t["path"] + " " + detail)
                else:
                    out.append("    [抽查] " + t["path"] + "  -- " + t["desc"] + "（" + detail + "）")
                    review.append("[" + r["id"] + "] " + t["path"] + " -- " + t["desc"])
            elif state == STATE_MANUAL:
                out.append("    [抽查] " + t["path"] + "  -- " + t["desc"])
                review.append("[" + r["id"] + "] " + t["path"] + " -- " + t["desc"])
            elif t["path"] in changed_set:
                out.append("    [已同步] " + t["path"] + "  -- " + t["desc"])
            else:
                out.append("    [未随本次改动更新] " + t["path"] + "  -- " + t["desc"])
                review.append("[" + r["id"] + "] " + t["path"] + " -- " + t["desc"])

    if unknown:
        out.append("")
        out.append("-" * 74)
        out.append("需人工确认")
        out.append("-" * 74)
        for u in unknown:
            out.append("  ! " + u)

    out.append("")
    out.append("-" * 74)
    out.append(
        "汇总：真欠账 " + str(len(debt)) + " 项 / 已覆盖 " + str(covered)
        + " 项 / 抽查 " + str(len(review)) + " 项 / 需人工确认 " + str(len(unknown)) + " 项"
    )
    out.append("-" * 74)
    if debt:
        out.append("")
        out.append("真欠账明细：")
        for m in debt:
            out.append("  x " + m)
    return "\n".join(out)


def main(argv=None):
    ap = argparse.ArgumentParser(description="改动影响面 -> 文档同步对账表（契约级）")
    ap.add_argument(
        "--base",
        default=None,
        help="对比区间；可写 HEAD~1（= HEAD~1..HEAD）或 'A~1..A'（审计单个提交）；默认取工作区 vs HEAD",
    )
    ap.add_argument("--json", action="store_true", help="输出 JSON")
    args = ap.parse_args(argv)

    changed = collect_changed(args.base)
    rules, unknown = classify(changed)

    if args.json:
        print(
            json.dumps(
                {
                    "base": args.base or "HEAD",
                    "changed": changed,
                    "rules": list(rules.values()),
                    "unknown": unknown,
                },
                ensure_ascii=False,
                indent=2,
            )
        )
    else:
        print(render(rules, unknown, changed, args.base))

    if unknown:
        return 2
    for r in rules.values():
        for t in r["targets"]:
            if t["state"] == STATE_CONTRACT and t.get("status") == "missing":
                return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
