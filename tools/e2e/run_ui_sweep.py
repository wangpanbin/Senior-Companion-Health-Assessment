# -*- coding: utf-8 -*-
"""
银龄伴诊 · Playwright UI 巡检（前后端联调验证）  v3
====================================================

思路：每个账号 = 「清干净登录态」+「注入真实令牌」+「逐页跑一段纯静态 Playwright 代码」。

    0. `playwright-cli -s=<session> run-code <清空脚本>`    ← v3 新增，见下方「假绿事故」
    1. `playwright-cli -s=<session> localstorage-set nianglin_access_token <token>`
       —— 令牌来自 harvest_tokens.py，是**真登录接口**签发的。
          只需要 accessToken：路由守卫在「有 token 但没 userInfo」时会自动
          `fetchCurrentUser()` 补拉，所以不必再塞 userInfo 的 JSON
          （那段 JSON 带双引号，正是 shell 传参最容易打碎的东西）。
    2. `playwright-cli -s=<session> run-code <JS>`          逐页巡检

━━━ v2 的假绿事故（v3 修的就是它，别再改回去）━━━
    v2 只覆盖 token、不清 userInfo。可 `frontend/src/utils/auth.js` 把
    `nianglin_user_info`（含 role）持久化在 localStorage，而路由守卫
    `router/index.js:70` 的判断是 `if (!userStore.userInfo) fetchCurrentUser()`
    ——**有缓存就不补拉**。于是第 2 个账号开始，守卫一直拿着上一个账号的 role：

        fam001 / admin 访问任何受限路由 → role=ELDER 不匹配 → 弹回 /elder/home

    实测后果：8 张 admin 截图和 9 张 fam001 截图的 **MD5 完全一致**，内容全是
    老人端首页；而报告依旧「33/33 全绿」—— 因为 elder/home 文本很长，
    躲过了「文本过短」的判据。**这是最危险的一类假绿：页面能渲染、无报错、
    文本丰富，但根本不是被测的那个页面。**

    两条防线（v3 都加了）：
      a) 每个账号开始前 `localStorage.clear()`，让守卫从 /auth/me 重新取角色；
      b) 每页断言 `new URL(page.url()).pathname === 目标路由`，不一致直接判红；
      c) 全跑完后按账号校验截图 MD5 是否重复，重复即报警（`duplicateShots`）。

    教训：**「页面没报错」不等于「你测的就是那个页面」。**
    断言里必须带上「我落在哪儿」这一条，否则重定向会把整个矩阵变成同一页的复读。

━━━ 三个必须绕开的 Windows 传参坑（都是实测踩出来的，别再改回去）━━━
  1. **必须用 `playwright-cli.cmd`**：npm 全局 shim 有三份（无扩展名 / `.cmd` / `.ps1`）。
     Python subprocess 走 CreateProcess，**不认 `.ps1`**（WinError 2）。
  2. **单条命令行有 8191 字符上限**（因为 `.cmd` 经由 cmd.exe）。
     把 33 个页面塞进一次调用会得到「命令行太长」且 stdout 为空 → 必须**逐页调用**。
  3. **JS 骨架必须是纯 ASCII**，且不要内联任何带双引号的 JSON：
     - 内联 JSON → cmd 吃掉双引号 → node 收到语法错误的函数体 →
       只报 `SyntaxError: Unexpected token ')'`，完全看不出真正原因。
     - 中文同样不该出现在参数里（会随控制台代码页变形）→ 路由标签留在 Python 侧。
     - 另外 `<` 在 cmd 里有输入重定向语义，骨架里一律不用。
  4. `run-code` 的函数体**既不是 Node 也不是普通页面上下文**：
     没有 `Buffer`、没有 `TextDecoder`（实测）。所以任何「在 JS 里解码 base64」的
     方案都不可行 —— 这也是最终改成静态骨架的原因。

判据：
    红 = 落地路径与目标路由不一致 / console error / pageerror / ≥400 的 API 响应 /
         被踢回 /login / 导航失败 / 取证失败
    黄 = 命中假数据特征词、文本异常短（<30 字），或整页没发任何 /api 请求
    绿 = 以上都没有

`/ws/` 的失败单独记到 wsNotes（不判红）：切换页面时 WebSocket 必然被销毁，
    这个 teardown 事件会落到下一轮探针里，当红判会造成假阳性。

用法（PowerShell）：
    $env:PYTHONUTF8=1
    python tools/e2e/run_ui_sweep.py
    只跑一个账号定位问题：$env:E2E_ONLY="admin"
"""

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
TOKENS = os.path.join(ROOT, "reports", "e2e", "tokens.json")
OUT_DIR = os.path.join(ROOT, "reports", "e2e")
SHOT_DIR = os.path.join(OUT_DIR, "shots")
# ⚠️ 前端 dev server 地址。默认 5173，但**必须可覆盖**：
# Windows 上 Hyper-V/WSL 会动态保留一批 TCP 端口（`netsh int ipv4 show excludedportrange protocol=tcp`），
# 5173 可能落进保留段（如 5152–5251）→ vite 启动直接 `EACCES: permission denied`，
# 表现为「session 预热失败」这种和前端代码毫无关系的假红。
# 换端口跑：`npm run dev -- --host 127.0.0.1 --port 5410` + `$env:NIANGLIN_APP="http://localhost:5410"`
APP = os.environ.get("NIANGLIN_APP", "http://localhost:5173")
MAX_JS_CHARS = 7000

# ============================================================
# 巡检矩阵：账号 → 要访问的路由
# 与 frontend/src/router/routes.js 一一对应；带 :id 的路由用真实种子 id，
# 见 docs/agents/FRONTEND_CONTRACT.md §10.8
# ============================================================
MATRIX = [
    {
        "username": "elder001",
        "label": "老人端 ELDER",
        "viewport": (390, 844),
        "routes": [
            ("/elder/home", "老人首页"),
            ("/elder/medication", "老人用药"),
            ("/elder/message", "消息（3 角色共用页）"),
            ("/profile", "我的"),
        ],
    },
    {
        "username": "fam001",
        "label": "家属端 FAMILY",
        "viewport": (390, 844),
        "routes": [
            ("/family/home", "家属工作台"),
            ("/family/elder", "我的老人"),
            ("/family/elder/bind", "绑定老人"),
            ("/family/medication", "家属用药管理"),
            ("/family/order", "我的订单"),
            ("/family/order/1001", "订单详情（PENDING 1001）"),
            ("/family/order/1031/review", "评价订单（已评价 1031）"),
            ("/family/order/1001/complaint", "我要投诉"),
            ("/family/message", "消息（3 角色共用页）"),
            ("/profile", "我的"),
        ],
    },
    {
        "username": "fam019",
        "label": "家属端 FAMILY（有 COMPLETED 订单）",
        "viewport": (390, 844),
        "routes": [
            ("/family/order/1019/review", "评价订单（COMPLETED 1019 happy path）"),
            ("/family/order/1019/complaint", "我要投诉（1019）"),
        ],
    },
    {
        "username": "comp007",
        "label": "陪诊员端 COMPANION（有 ACCEPTED 订单）",
        "viewport": (390, 844),
        "routes": [
            ("/companion/hall", "接单大厅"),
            ("/companion/entry", "资质入驻"),
            ("/companion/execute/1007", "订单执行（ACCEPTED 1007，含 WS 实时进度）"),
            ("/companion/order", "我的订单"),
            ("/companion/income", "我的收入"),
            ("/companion/message", "消息（3 角色共用页）"),
            ("/profile", "我的"),
        ],
    },
    {
        "username": "comp001",
        "label": "陪诊员端 COMPANION（无在途订单）",
        "viewport": (390, 844),
        "routes": [
            ("/companion/hall", "接单大厅"),
            ("/companion/income", "我的收入"),
        ],
    },
    {
        "username": "admin",
        "label": "管理后台 ADMIN",
        "viewport": (1440, 900),
        "routes": [
            ("/admin/dashboard", "W-01 数据看板"),
            ("/admin/companion-audit", "W-02 资质审核"),
            ("/admin/order-dispute", "W-03 订单纠纷仲裁"),
            ("/admin/user", "W-04 用户管理"),
            ("/admin/order", "W-05 订单管理"),
            ("/admin/complaint", "W-06 投诉管理"),
            ("/admin/export", "W-07 数据导出"),
            ("/admin/oper-log", "W-08 操作日志"),
        ],
    },
]

FAKE_HINTS = ["mock", "Mock", "MOCK", "示例数据", "假数据", "模拟数据", "lorem"]


def resolve_cli():
    """定位 playwright-cli.cmd（不能是 .ps1 —— subprocess 走 CreateProcess 不认它）。"""
    candidates = [
        os.environ.get("PLAYWRIGHT_CLI"),
        r"D:\nodejs\node_global\playwright-cli.cmd",
        shutil.which("playwright-cli.cmd"),
    ]
    try:
        npm_root = subprocess.run(
            ["npm", "root", "-g"], capture_output=True, text=True,
            encoding="utf-8", errors="replace", timeout=30
        ).stdout.strip()
        if npm_root:
            candidates.append(os.path.join(os.path.dirname(npm_root), "playwright-cli.cmd"))
    except Exception:
        pass
    for c in candidates:
        if c and os.path.exists(c):
            return c
    raise SystemExit("找不到 playwright-cli.cmd，请设置环境变量 PLAYWRIGHT_CLI 指向绝对路径。")


def build_js_route(username, path, width, height):
    """
    生成**单个页面**的巡检代码。**必须是纯 ASCII** —— 见文件头「传参坑」第 3 条。

    为什么是一页一次 run-code，而不是把整批页面塞进一次调用：
    只要有一个页面让某个 await 永久 pending，整批调用就再也不返回，
    **前面已经采到的证据全部作废**，而且完全看不出卡在哪一页（实测踩过）。
    按页拆开后，卡点被单独记成一条红项，其余页面照常出结论。

    路由标签（中文）不进参数：Python 侧本来就有，JS 只回传路径即可。
    """
    js = (
        "async page => {"
        " const APP='" + APP + "';"
        " const P='" + path + "';"
        " const rec={route:P,consoleErrors:[],apiFails:[],apiCalls:[],wsNotes:[]};"
        " const oc=(m)=>{ if(m.type()==='error') rec.consoleErrors.push(String(m.text()).slice(0,300)); };"
        " const oe=(e)=>{ rec.consoleErrors.push('PAGEERROR: '+String(e&&e.message).slice(0,300)); };"
        " const or=(res)=>{ const u=res.url(); if(u.indexOf('/api/')===-1) return;"
        "   const st=res.status();"
        "   const rel=u.replace(APP,'');"
        "   if(rec.apiCalls.length<40) rec.apiCalls.push(res.request().method()+' '+rel+' '+st);"
        "   if(st>=400) rec.apiFails.push(String(st)+' '+res.request().method()+' '+rel); };"
        " const of=(req)=>{ const u=req.url();"
        "   const why=String((req.failure()&&req.failure().errorText)||'').slice(0,100);"
        "   if(u.indexOf('/api/')!==-1) rec.apiFails.push('NETFAIL '+req.method()+' '+u.replace(APP,'')+' :: '+why);"
        "   else if(u.indexOf('/ws/')!==-1) rec.wsNotes.push('WSFAIL '+u.replace(APP,'')+' :: '+why); };"
        " page.on('console',oc); page.on('pageerror',oe); page.on('response',or); page.on('requestfailed',of);"
        " try{ await page.setViewportSize({width:" + str(width) + ",height:" + str(height) + "}); }catch(e){}"
        # 导航用 commit 再单独等 domcontentloaded：实测直接 goto(...,domcontentloaded) 偶发 30s 超时，
        # 而 commit 只要十几毫秒。等待失败**不致命** —— 页面的取证（控制台/接口/文本）
        # 比「是否等到某个 load 状态」重要，所以每步各包一层 try。
        " try{ await page.goto(APP+P,{waitUntil:'commit',timeout:20000}); }"
        " catch(e){ rec.navError=String(e&&e.message).slice(0,200); }"
        " try{ await page.waitForLoadState('domcontentloaded',{timeout:8000}); }catch(e){}"
        " await page.waitForTimeout(2500);"
        " rec.finalUrl=page.url();"
        # ⚠️ run-code 的**函数体**是裸环境：URL / Buffer / TextDecoder / location 全是 undefined
        # （实测 typeof URL === 'undefined'）。所以不能用 new URL(...).pathname ——
        # 那会静默落进 catch，finalPath 变空串，于是 33 页全被判「落地路径不符」假红。
        # 取路径只能进页面上下文（page.evaluate 里这些全局才存在），再加一层纯字符串兜底。
        " try{ rec.finalPath=await page.evaluate(() => location.pathname); }catch(e){ rec.finalPath=''; }"
        " if(!rec.finalPath){"
        "   let p=String(rec.finalUrl||'');"
        "   const sp=p.indexOf('://');"
        "   if(sp!==-1){ const sl=p.indexOf('/', sp+3); p = sl===-1 ? '/' : p.slice(sl); }"
        "   const qm=p.indexOf('?'); if(qm!==-1) p=p.slice(0,qm);"
        "   const hm=p.indexOf('#'); if(hm!==-1) p=p.slice(0,hm);"
        "   rec.finalPath=p;"
        " }"
        # v3 核心判据：落地路径必须就是目标路由。
        # 少了这一条，路由守卫的重定向会把「整个账号的每一页」变成同一页的复读，
        # 而报告照样全绿（见文件头「假绿事故」）。
        # 取不到路径时只记 pathUnknown，不当成 mismatch —— 否则取证工具的缺陷会变成一堆假红。
        " rec.pathUnknown = !rec.finalPath;"
        " rec.pathMismatch = !rec.pathUnknown && rec.finalPath !== P;"
        " try{ rec.title=await page.title(); }catch(e){ rec.title=''; }"
        " try{ rec.text=(await page.locator('body').innerText()).replace(/\\s+/g,' ').trim().slice(0,900); }catch(e){ rec.text=''; }"
        " const shot='reports/e2e/shots/" + username + "__" + re.sub(r"[^a-zA-Z0-9]+", "_", path).strip("_") + ".png';"
        " try{ await page.screenshot({path:shot,timeout:8000}); rec.shot=shot; }catch(e){ rec.shotError=String(e&&e.message).slice(0,120); }"
        " page.off('console',oc); page.off('pageerror',oe); page.off('response',or); page.off('requestfailed',of);"
        " if(rec.finalUrl && rec.finalUrl.indexOf('/login')!==-1 && P!=='/login') rec.redirectedToLogin=true;"
        " rec.passed = rec.consoleErrors.length===0 && rec.apiFails.length===0 && !rec.navError"
        "   && !rec.redirectedToLogin && !rec.pathMismatch && !!rec.text;"
        " return JSON.stringify(rec);"
        "}"
    )
    if not js.isascii():
        raise SystemExit(f"{username}{path} 的巡检代码含非 ASCII 字符，会随控制台代码页变形")
    if len(js) > MAX_JS_CHARS:
        raise SystemExit(f"{username}{path} 的巡检代码 {len(js)} 字符，超过安全线 {MAX_JS_CHARS}")
    return js


def cli_run(cli, args, timeout=300):
    return subprocess.run([cli] + args, cwd=ROOT, capture_output=True, text=True,
                          encoding="utf-8", errors="replace", timeout=timeout, shell=False)


def extract_result(stdout):
    """从 CLI 输出里抠出返回值（`### Result` 之后、下一个 `###` 之前）。"""
    idx = stdout.find("### Result")
    if idx == -1:
        return None
    tail = stdout[idx + len("### Result"):]
    nxt = tail.find("\n### ")
    if nxt != -1:
        tail = tail[:nxt]
    tail = tail.strip()
    tail = re.sub(r"^```[a-zA-Z]*\n", "", tail)
    tail = re.sub(r"\n```$", "", tail).strip()
    try:
        val = json.loads(tail)
    except json.JSONDecodeError:
        return None
    if isinstance(val, str):
        try:
            val = json.loads(val)
        except json.JSONDecodeError:
            return None
    return val


# 全程共用一个浏览器 session。
# 早期版本是「每个账号新建一个 session」，结果**每个新 session 的首次 goto 都超时**
# （浏览器刚起来、导航栈还没就绪），6 个账号全军覆没。
# 共用一个 session 就没这个问题；账号隔离改由「每账号清空 localStorage」保证。
SESSION = "e2e-ui"

_browser_ready = False


def ensure_browser(cli):
    """
    确保共享 session 已就绪（只在第一次调用时真正执行）。

    做法：先 close 清掉上次可能残留的 session（忽略失败），再 open，
    然后**用一次 run-code 预热**并校验当前 URL —— 只 open 不预热的话，
    紧接着的第一次 goto 仍可能超时。
    """
    global _browser_ready
    if _browser_ready:
        return

    cli_run(cli, ["-s=" + SESSION, "close"], timeout=120)
    cli_run(cli, ["-s=" + SESSION, "open", APP + "/login"], timeout=240)

    warm = "async page => { return JSON.stringify({ url: page.url() }); }"
    for attempt in range(1, 4):
        proc = cli_run(cli, ["-s=" + SESSION, "run-code", warm], timeout=120)
        if '"http' in (proc.stdout or ""):
            print(f"浏览器 session 就绪（预热第 {attempt} 次）\n")
            _browser_ready = True
            return
        print(f"   预热第 {attempt} 次未就绪，重试…")
        cli_run(cli, ["-s=" + SESSION, "close"], timeout=120)
        cli_run(cli, ["-s=" + SESSION, "open", APP + "/login"], timeout=240)
    raise SystemExit("浏览器 session 预热失败，无法开始巡检")


# v3 新增：换账号前把登录态清干净。
# 只覆盖 token 是不够的 —— `nianglin_user_info`（含 role）会被持久化，
# 而守卫「有缓存就不补拉」，于是新账号会顶着上一个账号的角色跑（见文件头「假绿事故」）。
RESET_JS = (
    "async page => {"
    " await page.goto('" + APP + "/login',{waitUntil:'commit',timeout:20000});"
    " await page.evaluate(() => { localStorage.clear(); sessionStorage.clear(); });"
    " return 'cleared';"
    "}"
)


def probe_account(cli, item, token):
    """一个账号：清登录态 → 注入令牌 → **逐页**巡检 → 回结果列表。

    逐页调用的好处：任何一页卡死只影响它自己（记一条红项），不会毁掉整批取证。
    """
    global _browser_ready

    # 支持只跑单个账号，便于定位问题：$env:E2E_ONLY="admin"
    only = os.environ.get("E2E_ONLY", "").strip()
    if only and only != item["username"]:
        return [], None

    ensure_browser(cli)

    def reset_and_inject():
        try:
            cli_run(cli, ["-s=" + SESSION, "run-code", RESET_JS], timeout=120)
        except subprocess.TimeoutExpired:
            return "清空登录态超时"
        rr = cli_run(cli, ["-s=" + SESSION, "localstorage-set", "nianglin_access_token", token],
                     timeout=120)
        if "### Error" in (rr.stdout or ""):
            return "注入 accessToken 失败"
        return None

    err = reset_and_inject()
    if err:
        return None, err

    results, raws = [], []
    routes = item["routes"]
    for idx, (path, label) in enumerate(routes, 1):
        js = build_js_route(item["username"], path, *item["viewport"])
        rec, last_err = None, None

        for attempt in (1, 2):
            try:
                proc = cli_run(cli, ["-s=" + SESSION, "run-code", js], timeout=90)
            except subprocess.TimeoutExpired:
                last_err = "run-code 超时 90s（该页有某个 await 永不返回）"
                # 超时会把 session 搞成不确定状态 → 重开浏览器再来
                cli_run(cli, ["-s=" + SESSION, "close"], timeout=60)
                _browser_ready = False
                ensure_browser(cli)
                reset_and_inject()
                continue

            raws.append(f"##### {path} (attempt {attempt}) #####\n{proc.stdout}\n--- stderr ---\n{proc.stderr}\n")
            rec = extract_result(proc.stdout)
            if rec is not None:
                break

            last_err = (proc.stdout or proc.stderr or "").strip().replace("\n", " | ")[:300]
            if attempt == 1:
                _browser_ready = False
                ensure_browser(cli)
                reset_and_inject()

        if rec is None:
            rec = {"route": path, "consoleErrors": [], "apiFails": [], "apiCalls": [], "wsNotes": [],
                   "navError": f"巡录取证失败: {last_err}", "passed": False}
        rec["account"] = item["username"]
        rec["roleLabel"] = item["label"]
        rec["label"] = label
        rec.setdefault("shot", "reports/e2e/shots/" + item["username"] + "__"
                       + re.sub(r"[^a-zA-Z0-9]+", "_", path).strip("_") + ".png")
        results.append(rec)

        flag = "OK" if rec.get("passed") else "!!"
        # 落地路径一定要打出来：这是「我到底测的是不是那一页」的唯一直接证据
        extra = "land=" + (rec.get("finalPath") or "(unknown)")
        if rec.get("pathMismatch"):
            extra += f"  <<< MISMATCH（目标 {path}）"
        if rec.get("navError"):
            extra += "  nav=" + rec["navError"][:60]
        print(f"   [{idx}/{len(routes)}] {flag} {path:<36} jsErr={len(rec.get('consoleErrors', []))} "
              f"apiErr={len(rec.get('apiFails', []))} apiCall={len(rec.get('apiCalls', []))} {extra}")

    with open(os.path.join(OUT_DIR, f"ui_sweep.{item['username']}.raw.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(raws))
    return results, None


def find_duplicate_shots(results):
    """
    同一账号内多页截图 MD5 相同 = 强烈提示重定向把矩阵变成了同一页的复读。
    这是 v2 假绿事故的自动哨兵，比人眼看截图可靠得多。
    """
    by_md5 = {}
    for r in results:
        shot = r.get("shot")
        if not shot:
            continue
        full = os.path.join(ROOT, shot.replace("/", os.sep))
        if not os.path.exists(full):
            continue
        with open(full, "rb") as fh:
            h = hashlib.md5(fh.read()).hexdigest()
        by_md5.setdefault((r["account"], h), []).append(r["route"])
    dupes = []
    for (acct, h), routes in by_md5.items():
        if len(routes) >= 2:
            dupes.append({"account": acct, "routes": routes, "md5": h[:10]})
    return dupes


def main():
    if not os.path.exists(TOKENS):
        raise SystemExit(f"找不到 {TOKENS}，先跑 harvest_tokens.py")
    with open(TOKENS, "r", encoding="utf-8") as f:
        tokens = json.load(f)

    os.makedirs(SHOT_DIR, exist_ok=True)
    cli = resolve_cli()

    total_routes = sum(len(m["routes"]) for m in MATRIX)
    print(f"巡检矩阵：{len(MATRIX)} 个账号 / {total_routes} 个页面")
    print(f"playwright-cli: {cli}\n")

    all_results, errors = [], []
    for item in MATRIX:
        rec = (tokens.get("accounts") or {}).get(item["username"])
        if not rec:
            errors.append(f"{item['username']}: tokens.json 里没有该账号")
            continue
        res, err = probe_account(cli, item, rec["accessToken"])
        if err:
            errors.append(f"{item['username']}: {err}")
            print(f"→ {item['username']:<9} {item['label']:<32} [X] {err}")
            continue
        all_results.extend(res)
        reds = [r for r in res if not r.get("passed")]
        print(f"→ {item['username']:<9} {item['label']:<32} {len(res)} 页，异常 {len(reds)}")

    if not all_results:
        print("\n一个页面都没巡检到。")
        for e in errors:
            print("   ", e)
        return 2

    dupes = find_duplicate_shots(all_results)
    if dupes:
        errors.append("截图 MD5 重复（疑似重定向把多页变成同一页）：" + json.dumps(dupes, ensure_ascii=False))

    # ---------- 判定 ----------
    red, yellow, green = [], [], []
    for r in all_results:
        text = r.get("text") or ""
        r["fakeHints"] = [h for h in FAKE_HINTS if h in text]
        r["shortText"] = len(text) < 30
        r["noApiCalls"] = len(r.get("apiCalls") or []) == 0
        if not r.get("passed"):
            red.append(r)
        elif r["fakeHints"] or r["shortText"] or r["noApiCalls"]:
            yellow.append(r)
        else:
            green.append(r)

    summary = {
        "total": len(all_results),
        "green": len(green), "yellow": len(yellow), "red": len(red),
        "harnessErrors": errors,
        "duplicateShots": dupes,
        "redDetail": [{
            "account": r["account"], "route": r["route"], "label": r.get("label"),
            "consoleErrors": r.get("consoleErrors", [])[:6],
            "apiFails": r.get("apiFails", [])[:10],
            "navError": r.get("navError"),
            "pathMismatch": r.get("pathMismatch", False),
            "finalPath": r.get("finalPath"),
            "redirectedToLogin": r.get("redirectedToLogin", False),
            "finalUrl": r.get("finalUrl"),
            "text": (r.get("text") or "")[:300],
        } for r in red],
        "yellowDetail": [{
            "account": r["account"], "route": r["route"], "label": r.get("label"),
            "fakeHints": r["fakeHints"], "shortText": r["shortText"], "noApiCalls": r["noApiCalls"],
            "text": (r.get("text") or "")[:240],
        } for r in yellow],
    }
    with open(os.path.join(OUT_DIR, "ui-sweep.json"), "w", encoding="utf-8") as f:
        json.dump({"summary": summary, "results": all_results}, f, ensure_ascii=False, indent=2)

    # 报告同时写文件：PowerShell 5.1 对原生命令的 stderr/stdout 重定向不可靠，
    # 依赖控制台捕获会得到 0 字节文件，所以把结论直接落盘。
    lines = []
    lines.append("# Playwright UI 巡检报告（前后端联调）\n")
    lines.append(f"- 巡检页面总数：**{len(all_results)}**")
    lines.append(f"- 绿：{len(green)} / 黄：{len(yellow)} / 红：{len(red)}")
    if errors:
        lines.append(f"- 采集器异常：{errors}")
    lines.append("\n## 红项（必须修）\n")
    for r in red:
        lines.append(f"### `{r['account']}` · `{r['route']}` — {r.get('label', '')}")
        if r.get("pathMismatch"):
            lines.append(f"- **落地路径不符**：目标 `{r['route']}`，实际 `{r.get('finalPath')}`"
                         f"（路由守卫重定向；role 缓存没清干净会全账号复读同一页）")
        if r.get("navError"):
            lines.append(f"- 导航失败：{r['navError']}")
        if r.get("redirectedToLogin"):
            lines.append("- 被重定向到 `/login`（登录态未生效，或页面直接崩了）")
        for e in (r.get("consoleErrors") or [])[:6]:
            lines.append(f"- JS 错误：`{e[:300]}`")
        for a in (r.get("apiFails") or [])[:10]:
            lines.append(f"- 接口异常：`{a[:300]}`")
        lines.append("")
    if not red:
        lines.append("（无）\n")
    lines.append("## 黄项（需人工确认）\n")
    for r in yellow:
        lines.append(f"- `{r['account']}` · `{r['route']}` — 假数据痕迹={r['fakeHints']} "
                     f"文本过短={r['shortText']} 未发接口请求={r['noApiCalls']}")
    lines.append("\n## 全部页面明细\n")
    lines.append("| 账号 | 路由 | 说明 | 结果 | 落地路径 | JS 错 | 接口异常 | 接口调用数 |")
    lines.append("|---|---|---|---|---|---|---|---|")
    for r in sorted(all_results, key=lambda x: (x["account"], x["route"])):
        marks = "绿" if r in green else ("黄" if r in yellow else "红")
        lines.append(f"| `{r['account']}` | `{r['route']}` | {r.get('label', '')} | {marks} | "
                     f"`{r.get('finalPath', '')}` | {len(r.get('consoleErrors', []))} | "
                     f"{len(r.get('apiFails', []))} | {len(r.get('apiCalls', []))} |")

    report = "\n".join(lines) + "\n"
    with open(os.path.join(OUT_DIR, "ui-sweep-report.md"), "w", encoding="utf-8") as f:
        f.write(report)

    # 控制台也打一份（可能被 PowerShell 吞掉，所以上面的文件才是准的）
    print(report)

    return 1 if red else 0


if __name__ == "__main__":
    # 自我 tee：把控制台输出（含异常栈）落到文件。
    # 原因：PowerShell 5.1 对原生命令的 stdout/stderr 重定向不可靠 ——
    # 会出现「脚本明明跑了，捕获文件却是 0 字节」，而 cmd.exe 又被安全策略禁用。
    # 所以让 Python 自己写日志，不依赖调用方怎么重定向。
    import builtins
    import traceback

    os.makedirs(OUT_DIR, exist_ok=True)
    _log_path = os.path.join(OUT_DIR, "ui-sweep-console.txt")
    _logf = open(_log_path, "w", encoding="utf-8")
    _orig_print = builtins.print

    def _tee(*a, **k):
        _orig_print(*a, **k)
        try:
            _logf.write(" ".join(str(x) for x in a) + "\n")
            _logf.flush()
        except Exception:
            pass

    builtins.print = _tee
    try:
        _code = main()
    except SystemExit as e:
        print(f"SystemExit: {e}")
        _code = 1
    except Exception:
        print("未捕获异常：\n" + traceback.format_exc())
        _code = 1
    finally:
        print(f"exit={_code}  (日志见 {_log_path})")
        _logf.close()
    sys.exit(_code)
