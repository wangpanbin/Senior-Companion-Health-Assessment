# -*- coding: utf-8 -*-
r"""
银龄伴诊 · E4 评价公信力闭环 · 跨进程 e2e 脚本
====================================================

覆盖 PRD §13.3 的 5 条演示断言（`docs/prd/PRD-E4-评价公信力闭环.md`）：

  1. 回复 → 家属端 ≤ 3 秒收到站内信
  2. 裁定 → 立即查 score，与手工 SQL `AVG(score) WHERE is_valid=1` 一致
  3. 裁定后陪诊员公开评价列表条数 −1、家属订单详情该评价带标注
  4. 陪诊员 A 用 B 的评价 ID 回复 → `3004`
  5. 全站 grep「建议服用 / 推荐剂量」→ 空

**为什么需要这个脚本（单测盖不住什么）**
------------------------------------
后端已有的三个测试类锁的是「Service 拿到这些数据之后怎么算」：

  · `ReviewRulingServiceTest`  —— 裁定的四个事务动作（Mockito 级别）
  · `AdminControllerReviewTest` —— 裁定的 HTTP 契约
  · `ReviewAccessMatrixTest`    —— 越权矩阵

它们**全部是进程内的**：注解、Service 调用、Mock 的 mapper。
本脚本走的是**真实 HTTP + 真实 MySQL + 真实 Redis**，补的是那一层：

  · 鉴权是真的（登录 → JWT → 过滤器链 → @PreAuthorize）
  · 事务是真的（回复 / 裁定各自一个真实事务，跨进程可见）
  · 通知是真的（消息落库 + SSE 推送）
  · 越权是真的（用 B 的令牌打 A 的评价，后端自己判 3004）

**造夹具与自清**
--------------
脚本**自己造夹具**：往 `order_review` 直接插一条「未回复 + 属于某陪诊员」的评价
（挂在一条 COMPLETED 且**尚无评价**的订单上），跑完把这条及其副作用**全部删掉**。

不依赖种子数据的当前状态 —— 种子评价早已被回复过，
拿种子行当「未回复」用，脚本第二次跑就会撞上 `6006 已回复`。

自清覆盖：夹具评价行、本次产生的站内信、审计日志行、
`companion_profile` 的评分快照、Redis 的评分/未读缓存、本次登录产生的登录日志。

**口令与外部依赖**
----------------
· 口令一律走环境变量 `MYSQL_PASSWORD`（**不内置默认口令**，缺失直接退出 2）
· 种子账号口令取自 `backend/sql/tools/fixture_credentials.py`（单一真源）
· HTTP 与 MySQL 都复用 `tools/e2e/` 下既有助手，不引第三方库：
  `_probe_http.http`（urllib）+ `fixture.py` 的 `q` / `exec_sql` / `scalar` / `redis`
  —— 本机 Python 没有 pymysql，也不需要 redis 包

用法（PowerShell）
------------------
    $env:PYSQLUTF8 = 1
    $env:PYTHONUTF8 = 1
    $env:PYTHONIOENCODING = "utf-8"
    $env:MYSQL_PASSWORD = "<你的本机 dev 口令，不入库>"
    $env:SPRING_PROFILES_ACTIVE = "dev"
    .\tools\e2e\start-services.ps1          # 先起后端
    python tools/e2e/e2e_review_appeal.py             # dry-run：只做只读前置 + 打印计划
    python tools/e2e/e2e_review_appeal.py --execute   # 真跑（二次确认后写库）
    python tools/e2e/e2e_review_appeal.py --execute --yes   # 跳过确认（自动化场景）
    .\tools\e2e\stop-services.ps1

⚠️ **默认 dry-run**（`tools/e2e/README.md` 红线 3）：不加 `--execute` 时脚本**一个字节都不写** ——
不登录（登录会写 Redis 验证码与 `sys_login_log`）、不插夹具评价、不改评分快照。
它只做三件只读的事：探后端 `/health`、查四个种子账号是否存在、挑一条可用的夹具订单，
然后把**即将写哪些表**逐条打印出来。

退出码：0 = 五条断言全过（或 dry-run 正常结束）；
1 = 有断言失败、**或 `MYSQL_PASSWORD` 未设置**（红线 2 明确要求 exit 1，与断言失败同码）；
2 = 其它前置缺失（后端没起 / 种子账号不存在 / 找不到可用夹具订单）。
"""

import argparse
import importlib.util
import io
import json
import os
import sys
import time
from pathlib import Path

# ⚠️ 中文 Windows 控制台默认代码页是 GBK(cp936)：「✅」(U+2705) 编不出来，
# 最后那句 print 会抛 UnicodeEncodeError，让**所有检查都已通过**的脚本以退出码 1 结束
# （`fixture.py` 踩过同一个坑）。这里把 stdout / stderr 统一切到 UTF-8。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

ROOT = Path(__file__).resolve().parents[2]
TOOLS_E2E = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS_E2E))

# 复用既有助手：HTTP 走 urllib（_probe_http），MySQL/Redis 走 mysql.exe / redis-cli（fixture）
from _probe_http import http  # noqa: E402
import fixture  # noqa: E402

# ------------------------------------------------------------------
# 常量
# ------------------------------------------------------------------

#: 断言 1 的时限（秒）。PRD §13.3 第 1 条：回复 → 家属端 ≤ 3 秒收到站内信。
MESSAGE_DEADLINE_SECONDS = 3.0

#: 裁定理由长度下限（与后端 `ReviewRulingDTO` 的 `@Size(min = 10)` 一致）
REASON_MIN = 10

#: 断言 5 的违禁词（PRD §13.3 第 5 条原文：「建议服用 / 推荐剂量」）
FORBIDDEN_WORDS = ("建议服用", "推荐剂量")

#: 断言 5 的扫描面 —— 只扫业务代码，不扫文档与测试资源
SCAN_ROOTS = ("backend/src/main", "frontend/src", "ai-service/app")

#: 断言 5 的扫描面里要读的文本文件后缀
SCAN_EXTENSIONS = (".java", ".js", ".vue", ".py", ".json", ".txt",
                   ".yml", ".yaml", ".scss", ".css", ".html")

#: 断言 5 的 allowlist —— 命中是**合法**的，每条必须写清「为什么合法」。
#:
#: ⚠️ 这份清单是「显式」的：新增一个词表文件却忘了加进来，脚本会红并指路。
#: 隐式豁免（按目录 / 按模式跳过）会让它悄悄失效 —— 那正是本脚本要防的事。
#:
#: 清单是**实测**出来的（对三个扫描面逐文件逐行 grep），不是凭印象写的。
FORBIDDEN_WORD_ALLOWLIST = {
    "backend/src/main/java/org/company/nianglin/common/ResultCode.java":
        "合规错误码的文案常量（如「内容包含敏感词，请修改后重试」）—— "
        "拦截器对外说的话本来就要点明违禁表述",
    "backend/src/main/java/org/company/nianglin/util/ComplianceCheckUtil.java":
        "医疗建议词表的守卫器本体：REQUIRED_WORDS 常量与 javadoc 里逐条解释每个词为什么在表上",
    "backend/src/main/resources/compliance/medical-advice-words.txt":
        "医疗建议词表的**单一真源**（Java 与 Python 共读同一份）—— 表里有这些词是定义本身",
    "frontend/src/utils/elderHealth.js":
        "老人健康页的「禁止判断性表述」常量数组与注释 —— 词出现在禁用清单里是它的用途",
    "frontend/src/utils/__tests__/elderHealth.test.js":
        "上一条的**反面断言**用例：断言禁用词表里确实含这些词",
    "frontend/src/components/NlMobileOnlyNotice.vue":
        "提示文案里的合规声明注释（「不出现『建议服用 / 推荐剂量 / 诊断为』等表述」）",
    "frontend/src/views/elder/medication.vue":
        "药品页的免责声明文案（明确声明平台不做诊断、不开药方）",
    "frontend/src/__tests__/data-loop.test.js":
        "违禁词的反面断言用例集 —— 词出现在断言里是它的用途",
    "ai-service/app/paths.py":
        "助手服务的守卫词常量（REQUIRED_COMPLIANCE_WORDS）—— 与 Java 侧同一份词表",
}

# ------------------------------------------------------------------
# 账号与夹具选取
# ------------------------------------------------------------------

#: 夹具的四个角色。评价的陪诊员 = 家属 = 订单的下单人，
#: 另加一个「别的陪诊员」用来验证越权（断言 4）。
ACCOUNTS = {
    "family": "fam025",     # 夹具订单的下单家属
    "companion": "comp001",  # 夹具评价的陪诊员（归属本人）
    "other_companion": "comp007",  # 别的陪诊员（越权方）
    "admin": "admin",
}


def load_seed_password():
    """取种子账号口令（单一真源：`backend/sql/tools/fixture_credentials.py`）。

    那个文件用环境变量 `NIANGLIN_SEED_PASSWORD` 覆盖默认值，
    这里原样 import 它，**不自己抄一份默认值** ——
    抄一份必然先过期（那个文件的 docstring 就是这么写的）。
    """
    path = ROOT / "backend" / "sql" / "tools" / "fixture_credentials.py"
    if not path.is_file():
        die("找不到种子凭据真源：%s" % path)
    spec = importlib.util.spec_from_file_location("_seed_credentials", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.PASSWORD


def die(msg, code=2):
    """前置缺失退出。

    `code` 默认 2（后端没起 / 账号不存在这类**环境**问题）；
    环境变量缺失走 `code=1` —— `tools/e2e/README.md` 红线 2 写死的就是「`MYSQL_PASSWORD`
    未设置时直接 `exit 1`」，与断言失败同码（都是「需要一个人类去修」的失败）。
    曾经这里所有情况一律 exit 2，红线 2 写下的 `exit 1` 从来没生效过。
    """
    print("[FATAL] " + msg)
    sys.exit(code)


# ------------------------------------------------------------------
# HTTP 小封装（包在 _probe_http.http 之上）
# ------------------------------------------------------------------

def api(method, path, body=None, token=None, timeout=20):
    """打一个后端接口，返回 (http_status, code, data)。

    `_probe_http.http` 返回的是**完整信封** `{code, message, data}`，
    这里把信封拆开，业务代码只看 `code` 与 `data`。
    """
    status, parsed = http(method, path, body=body, token=token, timeout=timeout)
    if not isinstance(parsed, dict):
        return status, None, None
    return status, parsed.get("code"), parsed.get("data")


def login(account, password):
    """真登录，取 accessToken。

    验证码明文存在 Redis 键 `captcha:{key}`（dev 配置，**一次性消费**），
    所以走的是「取验证码 → 从 Redis 读明文 → 登录」三步 —— 与
    `frontend/e2e/fixtures/captcha.js` 同一套办法（无需 OCR）。
    """
    status, code, data = api("GET", "/auth/captcha")
    if code != 200 or not data:
        die("取验证码失败（HTTP %s code=%s）—— 后端没起来？" % (status, code))
    captcha_key = data.get("captchaKey")

    rc, out, err = fixture.redis(["GET", "captcha:" + captcha_key])
    if rc != 0 or not out or out == "(nil)":
        die("读不到验证码明文（Redis）：rc=%s out=%r err=%r" % (rc, out, err))
    captcha_code = out.strip().strip('"')

    status, code, data = api("POST", "/auth/login", {
        "username": account,
        "password": password,
        "captchaKey": captcha_key,
        "captchaCode": captcha_code,
    })
    if code != 200 or not data or not data.get("accessToken"):
        die("登录失败（账号 %s，HTTP %s code=%s）" % (account, status, code))
    return data["accessToken"]


# ------------------------------------------------------------------
# SQL 小封装
# ------------------------------------------------------------------

def scalar(sql, default=None):
    return fixture.scalar(sql, default)


def rows(sql):
    return fixture.q(sql)


# ------------------------------------------------------------------
# 夹具：造一条「未回复 + 属于某陪诊员」的评价
# ------------------------------------------------------------------

def pick_fixture_order(family_user_id, companion_user_id):
    """挑一条可用的订单：下单家属 = family_user_id、陪诊员 = companion_user_id、
    状态 COMPLETED、且**尚无评价**。

    「尚无评价」是硬要求：`order_review` 上有 `uk_order_id` 唯一索引，
    一单只能一条评价；且脚本要往这条订单上插夹具，撞了就是插入失败。
    """
    sql = (
        "SELECT o.id, o.order_no, o.elder_id FROM companion_order o "
        "LEFT JOIN order_review r ON r.order_id = o.id AND r.deleted = 0 "
        "WHERE o.deleted = 0 AND o.status = 'COMPLETED' "
        "AND o.family_id = %d AND o.companion_id = %d AND r.id IS NULL "
        "ORDER BY o.id LIMIT 5;" % (family_user_id, companion_user_id)
    )
    found = rows(sql)
    if not found:
        die("找不到可用夹具订单：family=%d companion=%d 且 COMPLETED 且无评价" %
            (family_user_id, companion_user_id))
    return int(found[0][0]), found[0][1], (int(found[0][2]) if found[0][2] else None)


def user_id_of(username):
    """按用户名查 user_id（账号不存在直接退出 2 —— 前置缺失，不是断言失败）。"""
    got = scalar("SELECT id FROM sys_user WHERE username = '%s' AND deleted = 0;" % username)
    if not got:
        die("种子账号不存在：%s" % username)
    return int(got)


def insert_fixture_review(order_id, order_no, family_id, elder_id, companion_id):
    """插一条夹具评价：score=1、未回复、is_valid=1。

    score 取 1 是有意的：它与该陪诊员的其它评价（种子都是 4~5 星）不同，
    裁定无效后平均分必然变化 —— 断言 2 才看得出「评分真的重算了」。

    ⚠️ INSERT 与 SELECT LAST_INSERT_ID() 必须在**同一次 mysql 调用**里：
    每次 `mysql.exe -e` 都是新连接，而 LAST_INSERT_ID() 是**连接级**的
    （同连接内最近一次自增），换个连接查出来永远是 0。
    """
    sql = (
        "INSERT INTO order_review "
        "(order_id, order_no, family_id, elder_id, companion_id, score, tags, content, "
        " is_anonymous, companion_reply, reply_time, is_valid, create_time) "
        "VALUES (%d, '%s', %d, %s, %d, 1, '[\"准时\"]', "
        "        'e2e 夹具评价：用于验证评价回复与有效性裁定链路。', "
        "        0, NULL, NULL, 1, NOW()); "
        "SELECT LAST_INSERT_ID();"
        % (order_id, order_no, family_id,
           (str(elder_id) if elder_id else "NULL"), companion_id)
    )
    got = rows(sql)
    new_id = int(got[-1][0]) if got and got[-1] and got[-1][0] else 0
    if not new_id:
        die("夹具评价插入失败（LAST_INSERT_ID=%s）" % new_id)
    return new_id


# ------------------------------------------------------------------
# 断言
# ------------------------------------------------------------------

class Result(object):
    """一条断言的结果。"""

    def __init__(self, no, title, ok, detail):
        self.no = no
        self.title = title
        self.ok = ok
        self.detail = detail

    def render(self):
        mark = "PASS" if self.ok else "FAIL"
        return "[%s] 断言 %s · %s —— %s" % (mark, self.no, self.title, self.detail)


def assert4_cross_companion_reply(other_token, review_id):
    """断言 4：陪诊员 A 用 B 的评价 ID 回复 → 3004。

    判据用**业务码**而不是 HTTP 状态码：后端对这类越权返的是
    HTTP 200 + 信封里的 `code=3004`（统一响应结构，见 `docs/api/README.md` §三）。
    """
    status, code, _ = api("POST", "/review/%d/reply" % review_id,
                          {"content": "越权回复尝试：这条评价不属于我。"}, other_token)
    ok = (code == 3004)
    return Result(4, "陪诊员 A 用 B 的评价 ID 回复 → 3004", ok,
                  "HTTP %s，业务码 %s（期望 3004）" % (status, code))


def assert1_family_message(family_token, order_id, deadline=MESSAGE_DEADLINE_SECONDS):
    """断言 1：回复 → 家属端 ≤ 3 秒收到站内信。

    判据是**家属令牌能查到这条消息**（`GET /api/message?type=REVIEW_REPLIED`），
    且耗时在时限内。

    为什么用 REST 查而不是读 SSE：消息落库与回复在**同一个事务**里，
    SSE 推送也在同一次 `messageService.send` 调用中发出 ——
    所以「消息落库且对家属可见」与「SSE 已推送」是同一个时刻的两种观测。
    SSE 通道本身的连通性由 `frontend/e2e/specs/11-realtime.spec.js` 覆盖。
    """
    started = time.monotonic()
    deadline_at = started + deadline
    hit = None
    polled = 0
    while time.monotonic() < deadline_at:
        polled += 1
        status, code, data = api("GET", "/message?type=REVIEW_REPLIED&page=1&size=50",
                                 token=family_token)
        if code == 200 and data:
            for record in (data.get("records") or []):
                # biz_id = 订单 ID：确认这条消息正是本次回复产生的那条，
                # 而不是库里恰好存在的别的 REVIEW_REPLIED
                if str(record.get("bizId")) == str(order_id):
                    hit = record
                    break
        if hit:
            break
        time.sleep(0.1)
    elapsed = time.monotonic() - started
    ok = hit is not None and elapsed <= deadline
    if hit:
        detail = ("耗时 %.2fs（上限 %.1fs），轮询 %d 次；消息 id=%s type=%s bizId=%s"
                  % (elapsed, deadline, polled, hit.get("id"), hit.get("type"), hit.get("bizId")))
    else:
        detail = ("耗时 %.2fs 仍未查到（上限 %.1fs，轮询 %d 次）" % (elapsed, deadline, polled))
    return Result(1, "回复 → 家属端 ≤ %.0f 秒收到站内信" % deadline, ok, detail)


def assert2_score_matches_sql(companion_id, api_score, sql_avg, sql_count):
    """断言 2：裁定 → 立即查 score，与手工 SQL 一致。

    手工 SQL 的口径与后端 `ReviewReadMapper.selectAverageScore` **逐字同构**：
    `is_valid = 1 AND deleted = 0`（被裁定无效的不参与聚合）。
    """
    api_avg_text = api_score.get("averageScore")
    try:
        api_avg = round(float(api_avg_text), 2)
    except (TypeError, ValueError):
        api_avg = None
    try:
        expect_avg = round(float(sql_avg), 2)
    except (TypeError, ValueError):
        expect_avg = None

    avg_ok = api_avg is not None and expect_avg is not None and api_avg == expect_avg
    count_ok = api_score.get("totalCount") == sql_count
    ok = avg_ok and count_ok
    detail = ("接口 averageScore=%s（手工 SQL %s）· totalCount=%s（SQL %s）"
              % (api_avg_text, sql_avg, api_score.get("totalCount"), sql_count))
    return Result(2, "裁定 → 立即查 score 与手工 SQL 一致", ok, detail)


def assert3_ruling_visible(list_before, list_after, family_review_vo):
    """断言 3：裁定后公开列表条数 −1、家属订单详情该评价带标注。

    两件事构成「一隐一现」：
      · 隐 —— 公开的陪诊员评价列表不再收录这条（`is_valid=0` 被过滤）
      · 显 —— 家属在订单详情里仍能看到它，且带着「已裁定无效」的标注

    参数只收它真正用到的三个：先前那版还挂着 `companion_id` / `order_id` 两个
    一个字符都没用的形参，读的人会以为判据跟它们有关。
    """
    count_delta = list_before - list_after
    count_ok = count_delta == 1
    vo = family_review_vo or {}
    mark_ok = vo.get("isValid") is False
    ok = count_ok and mark_ok
    detail = ("公开列表 %d → %d（应 −1）· 家属订单详情 isValid=%s（应为 false）"
              % (list_before, list_after, vo.get("isValid")))
    return Result(3, "裁定后公开列表 −1、家属订单详情带无效标注", ok, detail)


def assert5_no_forbidden_words():
    """断言 5：全站 grep 违禁词 → 空（业务扫描面 + 显式 allowlist）。"""
    hits = []
    for rel_root in SCAN_ROOTS:
        base = ROOT / rel_root
        if not base.is_dir():
            continue
        for dirpath, dirnames, filenames in os.walk(str(base)):
            dirnames[:] = [d for d in dirnames
                           if d not in ("node_modules", "target", "dist", "__pycache__")]
            for filename in filenames:
                if not filename.endswith(SCAN_EXTENSIONS):
                    continue
                full = Path(dirpath) / filename
                rel = full.relative_to(ROOT).as_posix()
                if rel in FORBIDDEN_WORD_ALLOWLIST:
                    continue
                try:
                    text = io.open(str(full), "r", encoding="utf-8", errors="replace").read()
                except OSError:
                    continue
                for lineno, line in enumerate(text.splitlines(), 1):
                    for word in FORBIDDEN_WORDS:
                        if word in line:
                            hits.append("%s:%d 命中「%s」" % (rel, lineno, word))
    ok = not hits
    detail = ("扫描 %d 个业务目录，命中 %d 处%s"
              % (len(SCAN_ROOTS), len(hits),
                 ("" if ok else "：" + "；".join(hits[:10]))))
    return Result(5, "全站 grep「%s / %s」→ 空" % FORBIDDEN_WORDS, ok, detail)


# ------------------------------------------------------------------
# dry-run：写操作清单
# ------------------------------------------------------------------

class WritePlan(object):
    """dry-run 时收集「本该执行的写操作」，一个字节都不落库。

    接口与 `fixture` 模块的两个写入口同形（`exec_sql` / `redis`），
    所以 `cleanup()` 不需要为 dry-run 写分支 —— 把 writer 换掉就行。
    """

    def __init__(self):
        self.statements = []

    def exec_sql(self, sql):
        self.statements.append("SQL    " + " ".join(sql.split()))

    def redis(self, args):
        self.statements.append("REDIS  " + " ".join(str(a) for a in args))
        return 0, "", ""

    def render(self):
        for line in self.statements:
            print("    " + line)


# ------------------------------------------------------------------
# 前置（只读）
# ------------------------------------------------------------------

def preflight():
    """只读前置：口令 / 后端 / 种子账号 / 夹具订单。

    dry-run 与 `--execute` 走的是同一段 —— 先把「环境到底行不行」验完，
    再决定要不要写。否则 dry-run 绿了而 `--execute` 死在第一步，
    那个 dry-run 就是个假信号。
    """
    if not os.environ.get("MYSQL_PASSWORD"):
        # 红线 2：口令走环境变量；缺失按它的原文要求 exit 1
        die("环境变量 MYSQL_PASSWORD 未设置（DB 口令一律走环境变量，不入库）", code=1)

    status, code, data = api("GET", "/health")
    if status == 0 or code is None:
        die("后端不可达（HTTP %s）—— 先跑 tools/e2e/start-services.ps1" % status)
    print("[前置] 后端可达：%s" % (data.get("application") if isinstance(data, dict) else "?"))

    ids = {key: user_id_of(username) for key, username in ACCOUNTS.items()}
    print("[前置] 账号就位：%s(%d) / %s(%d) / %s(%d) / %s(%d)"
          % (ACCOUNTS["family"], ids["family"], ACCOUNTS["companion"], ids["companion"],
             ACCOUNTS["other_companion"], ids["other_companion"],
             ACCOUNTS["admin"], ids["admin"]))

    return ids, pick_fixture_order(ids["family"], ids["companion"])


# ------------------------------------------------------------------
# 自清
# ------------------------------------------------------------------

def cleanup(ctx, writer=None):
    """删掉脚本造出来的全部痕迹。

    逐张表按「本次之前的水位」划界 —— 比按业务键删更精确，
    不会误伤别的脚本或手测留下的同表数据。

    `writer` 默认为真写（`fixture`）；dry-run 传 `WritePlan` 只收集不执行。
    """
    writer = writer or fixture
    print("\n[cleanup] 清理夹具与副作用 …")
    review_id = ctx.get("review_id")

    if review_id:
        # 审计日志：裁定写的那一行（target_type=REVIEW / target_id=评价 ID）
        # 双重限定：id 超水位（本次新增）**且** target 指向本次夹具 ——
        # 只靠 target_id 虽也能精确命中，但加上水位后就算将来夹具 id 复用也不会误删。
        writer.exec_sql(
            "DELETE FROM admin_oper_log WHERE id > %d "
            "AND target_type = 'REVIEW' AND target_id = %d;"
            % (ctx.get("oper_log_max", 0), review_id))
        # 站内信：回复与裁定的通知（biz_id = 订单 ID）。
        # ⚠️ 按 id 水位删，不按 biz_id 删：订单上可能已有**种子**站内信
        #    （ORDER_CREATED / ORDER_COMPLETED 等），按 biz_id 删会把它们一起带走。
        #    水位 + type 双限定，只删本次新增的那两条 E4 通知。
        writer.exec_sql(
            "DELETE FROM internal_message WHERE id > %d "
            "AND type IN ('REVIEW_REPLIED', 'REVIEW_INVALIDATED');"
            % ctx.get("message_max", 0))
        # 夹具评价本体
        writer.exec_sql("DELETE FROM order_review WHERE id = %d;" % review_id)
        print("  [OK] 删除夹具评价 #%d 及其站内信 / 审计日志" % review_id)

    # 评分快照：裁定把 companion_profile 的 score / review_count 重算了，
    # 还原成跑之前的值（user_id 是业务键，不是代理键 id）
    snapshot = ctx.get("profile_before")
    if snapshot:
        writer.exec_sql(
            "UPDATE companion_profile SET score = %s, review_count = %d "
            "WHERE user_id = %d AND deleted = 0;"
            % (snapshot["score"], snapshot["review_count"], snapshot["companion_id"]))
        print("  [OK] 还原 companion_profile 评分快照（user_id=%s）" % snapshot["companion_id"])

    # Redis 缓存：评分缓存与未读数缓存（后端裁定会删评分缓存，
    # 但脚本自己读 score 又会把它写回去）
    companion_id = ctx.get("companion_id")
    if companion_id:
        writer.redis(["DEL", "companion:score:%d" % companion_id])
    for uid in ctx.get("unread_cache_users", []):
        writer.redis(["DEL", "message:unread:%d" % uid])
    print("  [OK] 清理 Redis 评分 / 未读缓存")

    # 登录日志：本次 4 次登录留下的行（按水位删，不碰别人的）
    login_log_max = ctx.get("login_log_max")
    if login_log_max is not None:
        writer.exec_sql("DELETE FROM sys_login_log WHERE id > %d;" % login_log_max)
        print("  [OK] 删除本次登录产生的 sys_login_log 行")


# ------------------------------------------------------------------
# 主流程
# ------------------------------------------------------------------

def main(argv=None):
    parser = argparse.ArgumentParser(
        description="E4 评价公信力闭环跨进程 e2e（写库；默认 dry-run）")
    parser.add_argument("--execute", action="store_true",
                        help="真正执行（不加则只做只读前置并打印写库计划，一个字节都不写）")
    parser.add_argument("--yes", action="store_true",
                        help="跳过二次确认（自动化场景）")
    args = parser.parse_args(argv)

    # 前置先跑：dry-run 与 --execute 验的是同一套环境，
    # 否则 dry-run 绿而 --execute 死在第一步，那个 dry-run 就是个假信号
    ids, fixture_order = preflight()
    family_id, companion_id = ids["family"], ids["companion"]
    order_id, order_no, elder_id = fixture_order
    print("[夹具] 订单 #%s（%s）下单家属=%d 陪诊员=%d 状态=COMPLETED 且无评价"
          % (order_id, order_no, family_id, companion_id))

    if not args.execute:
        plan = WritePlan()
        print("\n" + "=" * 74)
        print("dry-run：以下写操作**都没有执行**（加 --execute 才真跑）")
        print("=" * 74)
        plan.render()
        print("[写库计划] INSERT order_review ×1 · POST /review/{id}/reply ×1"
              " · POST /admin/review/{id}/validity ×1")
        print("[写库计划] DELETE admin_oper_log / internal_message / order_review 各 1 条"
              " + UPDATE companion_profile 评分快照 + DELETE sys_login_log（本次 4 行）")
        print("[写库计划] Redis：DEL companion:score:{id} · message:unread:{family,companion}")
        print("\n确认无误后跑：python tools/e2e/e2e_review_appeal.py --execute")
        return 0

    if not args.yes:
        ans = input("\n即将写入本机 dev 库（order_review / internal_message / admin_oper_log / "
                    "companion_profile / sys_login_log + Redis），跑完自动回滚。\n"
                    "确认继续？(输入 yes 继续，其它任意键退出) ").strip()
        if ans != "yes":
            print("已取消，未写入任何数据。")
            return 1

    password = load_seed_password()

    ctx = {
        "companion_id": companion_id,
        "unread_cache_users": [family_id, companion_id],
        "login_log_max": int(scalar("SELECT IFNULL(MAX(id), 0) FROM sys_login_log;", "0")),
        # 自清的水位：本次新增的行 id 都大于它，按它划界才不会误删种子行
        "message_max": int(scalar("SELECT IFNULL(MAX(id), 0) FROM internal_message;", "0")),
        "oper_log_max": int(scalar("SELECT IFNULL(MAX(id), 0) FROM admin_oper_log;", "0")),
    }

    # ---------- 登录 ----------
    family_token = login(ACCOUNTS["family"], password)
    companion_token = login(ACCOUNTS["companion"], password)
    other_token = login(ACCOUNTS["other_companion"], password)
    admin_token = login(ACCOUNTS["admin"], password)
    print("[前置] 四个账号登录成功")

    profile_before = rows(
        "SELECT score, review_count FROM companion_profile "
        "WHERE user_id = %d AND deleted = 0;" % companion_id)
    ctx["profile_before"] = {
        "companion_id": companion_id,
        "score": (profile_before[0][0] if profile_before else "0"),
        "review_count": int(profile_before[0][1]) if profile_before else 0,
    }

    review_id = insert_fixture_review(order_id, order_no, family_id, elder_id, companion_id)
    ctx["review_id"] = review_id
    ctx["order_id"] = order_id
    print("[夹具] 插入评价 #%d（1 星 / 未回复 / is_valid=1）" % review_id)

    results = []
    try:
        # ---- 断言 4：越权回复 ----
        results.append(assert4_cross_companion_reply(other_token, review_id))

        # ---- 断言 1：回复 → 家属 ≤3 秒收到站内信 ----
        status, code, data = api("POST", "/review/%d/reply" % review_id,
                                 {"content": "当日 08:20 已到院打卡，可在医院服务台查记录。"},
                                 companion_token)
        if code != 200:
            results.append(Result(1, "回复 → 家属端 ≤ 3 秒收到站内信", False,
                                  "回复接口失败：HTTP %s code=%s" % (status, code)))
        else:
            results.append(assert1_family_message(family_token, order_id))

        # ---- 裁定前的两个基线 ----
        _, _, before_list = api("GET", "/review/companion/%d?page=1&size=100" % companion_id,
                                token=admin_token)
        list_before = int((before_list or {}).get("total") or 0)

        # ---- 断言 2 / 3：管理员裁定 ----
        status, code, data = api(
            "POST", "/admin/review/%d/validity" % review_id,
            {"isValid": False,
             "reason": "家属描述与打卡记录明显不符，证据充分，裁定为无效评价。"},
            admin_token)
        if code != 200:
            results.append(Result(2, "裁定 → 立即查 score 与手工 SQL 一致", False,
                                  "裁定接口失败：HTTP %s code=%s" % (status, code)))
            results.append(Result(3, "裁定后公开列表 −1、家属订单详情带无效标注", False,
                                  "裁定未成功，后续断言无法进行"))
        else:
            # 裁定后立即查 score（后端裁定会删掉 Redis 评分缓存，这里验证确实重算了）
            _, _, score_vo = api("GET", "/review/companion/%d/score" % companion_id,
                                 token=admin_token)
            sql_row = rows(
                "SELECT ROUND(AVG(score), 2), COUNT(*) FROM order_review "
                "WHERE companion_id = %d AND is_valid = 1 AND deleted = 0;" % companion_id)
            sql_avg = sql_row[0][0] if sql_row else None
            sql_count = int(sql_row[0][1]) if sql_row else 0
            results.append(assert2_score_matches_sql(companion_id, score_vo or {},
                                                      sql_avg, sql_count))

            _, _, after_list = api("GET", "/review/companion/%d?page=1&size=100" % companion_id,
                                   token=admin_token)
            list_after = int((after_list or {}).get("total") or 0)
            _, _, family_review = api("GET", "/review/order/%d" % order_id, token=family_token)
            results.append(assert3_ruling_visible(list_before, list_after, family_review))

        # ---- 断言 5：违禁词 ----
        results.append(assert5_no_forbidden_words())

    finally:
        cleanup(ctx)

    print("\n" + "=" * 74)
    for item in results:
        print(item.render())
    print("=" * 74)
    failed = [r for r in results if not r.ok]
    if failed:
        print("e2e 失败：%d / %d 条断言未过" % (len(failed), len(results)))
        return 1
    print("e2e 通过：%d 条断言全绿 ✅" % len(results))
    return 0


if __name__ == "__main__":
    sys.exit(main())
