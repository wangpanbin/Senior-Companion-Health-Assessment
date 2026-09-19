# -*- coding: utf-8 -*-
"""
银龄伴诊 · 后端三处缺口真机探针
================================

对本次补齐的三个能力做**真实 HTTP** 验证（不是单测，不是 mock）：

  缺口 1  ELDER 拿不到自己的 elderId
          → GET /api/auth/me 与 GET /api/user/profile 的 UserInfoVO 应下发 elderId
          → 仅 ELDER 有值；FAMILY / COMPANION / ADMIN 该字段必须**不出现**（NON_NULL）

  缺口 2  无通用文件上传入口
          → POST /api/file/upload（multipart: file + bizType）应返回 {fileId,url,size}
          → bizType 白名单 COMPANION_CERT / COMPLAINT / AVATAR；CHECKIN 必须被拒
          → ELDER 调写接口必须 403（ElderReadOnlyInterceptor）

  缺口 3  订单列表 VO 缺结算字段，逼前端做 N+1 getOrder
          → GET /api/order 的 records[] 应含 paymentStatus / paymentStatusLabel / actualFee
          → 同时**必须仍不含** familyId / elderId / companionId / address / remark（隐私红线）

用法（PowerShell）：
    $env:PYTHONUTF8=1; $env:PYTHONIOENCODING="utf-8"
    python tools/e2e/probe_backend_gaps.py

前置：必须先跑过 `pnpm -C frontend exec playwright test --project=setup`
      让 reports/playwright/.auth/*.json 存在 —— 见 tools/e2e/_probe_http.py
"""

import json
import os
import sys
import urllib.error
import urllib.request
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _probe_http import read_token, http  # noqa: E402  共享 storageState + HTTP

# 隐私红线：列表 VO 里一旦出现这些字段就是回归
FORBIDDEN_IN_LIST = ["familyId", "elderId", "companionId", "address", "remark", "version"]

PNG_BYTES = bytes.fromhex("89504E470D0A1A0A0000000D4948445200000001000000010806000000")

results = []


def check(name, ok, detail):
    results.append((name, ok, detail))
    print(f"[{'OK  ' if ok else 'FAIL'}] {name:<38} {detail}")


def post_multipart(path, token, fields, files):
    """手工构造 multipart/form-data（urllib 不带这个能力）。"""
    boundary = "----NLBoundary" + uuid.uuid4().hex
    body = bytearray()
    for k, v in fields.items():
        body += f"--{boundary}\r\n".encode()
        body += f'Content-Disposition: form-data; name="{k}"\r\n\r\n'.encode()
        body += str(v).encode("utf-8") + b"\r\n"
    for k, (filename, content, ctype) in files.items():
        body += f"--{boundary}\r\n".encode()
        body += f'Content-Disposition: form-data; name="{k}"; filename="{filename}"\r\n'.encode()
        body += f"Content-Type: {ctype}\r\n\r\n".encode()
        body += content + b"\r\n"
    body += f"--{boundary}--\r\n".encode()

    req = urllib.request.Request(
        BASE + path,
        data=bytes(body),
        headers={
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "Authorization": "Bearer " + token,
            "Accept": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, {"_raw": raw[:300]}
    except Exception as e:
        return 0, {"_error": f"{type(e).__name__}: {e}"}


def main():
    print("前置: 三个账号的 storageState 已落 (见 _probe_http.py 错误信息的解决路径)\n")

    # ---------- 从 Playwright storageState 取三个账号的真令牌 ----------
    # 注意: role 仅用于报告打印(原本 login() 需要 userId 做密码哈希,现在 read_token 只需要 username)
    tokens = {}
    for username, role in [("elder001", "ELDER"), ("fam001", "FAMILY"), ("comp001", "COMPANION")]:
        try:
            tokens[role] = read_token(username)
        except (FileNotFoundError, KeyError) as e:
            check(f"读 storageState {username}", False, str(e))
            return 1

    # ---------- 缺口 1：elderId 下发 ----------
    st, body = http("GET", "/auth/me", token=tokens["ELDER"])
    me = body.get("data") or {}
    check(
        "缺口1 ELDER /auth/me 有 elderId",
        st == 200 and me.get("elderId") == 401,
        f"status={st} elderId={me.get('elderId')} (期望 401)",
    )

    st, body = http("GET", "/user/profile", token=tokens["ELDER"])
    prof = body.get("data") or {}
    check(
        "缺口1 ELDER /user/profile 有 elderId",
        st == 200 and prof.get("elderId") == 401,
        f"status={st} elderId={prof.get('elderId')} (期望 401)",
    )

    st, body = http("GET", "/auth/me", token=tokens["FAMILY"])
    fam_me = body.get("data") or {}
    check(
        "缺口1 FAMILY 无 elderId（不泄露）",
        st == 200 and "elderId" not in fam_me,
        f"status={st} keys含elderId={'elderId' in fam_me}",
    )

    st, body = http("GET", "/auth/me", token=tokens["COMPANION"])
    comp_me = body.get("data") or {}
    check(
        "缺口1 COMPANION 无 elderId",
        st == 200 and "elderId" not in comp_me,
        f"status={st} keys含elderId={'elderId' in comp_me}",
    )

    # ---------- 缺口 2：通用上传 ----------
    st, body = post_multipart(
        "/file/upload",
        tokens["FAMILY"],
        {"bizType": "COMPANION_CERT"},
        {"file": ("cert.png", PNG_BYTES, "image/png")},
    )
    up = body.get("data") or {}
    check(
        "缺口2 FAMILY 传 PNG 成功",
        st == 200 and body.get("code") == 200 and str(up.get("url", "")).startswith("/uploads/"),
        f"status={st} code={body.get('code')} url={up.get('url')} fileId={up.get('fileId')}",
    )
    uploaded_url = up.get("url")

    st, body = post_multipart(
        "/file/upload",
        tokens["FAMILY"],
        {"bizType": "CHECKIN"},
        {"file": ("x.png", PNG_BYTES, "image/png")},
    )
    check(
        "缺口2 bizType=CHECKIN 被拒",
        body.get("code") == 400,
        f"status={st} code={body.get('code')} msg={body.get('message')}",
    )

    st, body = post_multipart(
        "/file/upload",
        tokens["FAMILY"],
        {"bizType": "COMPLAINT"},
        {"file": ("evil.txt", b"hello world, not an image", "text/plain")},
    )
    check(
        "缺口2 魔数非法被拒",
        body.get("code") == 400,
        f"status={st} code={body.get('code')} msg={body.get('message')}",
    )

    st, body = post_multipart(
        "/file/upload",
        tokens["ELDER"],
        {"bizType": "COMPANION_CERT"},
        {"file": ("cert.png", PNG_BYTES, "image/png")},
    )
    check(
        "缺口2 ELDER 上传 403",
        st == 403,
        f"status={st} msg={body.get('message')}",
    )

    # ---------- 缺口 3：列表 VO 结算字段 ----------
    st, body = http("GET", "/order?page=1&size=5", token=tokens["FAMILY"])
    recs = (body.get("data") or {}).get("records") or []
    if not recs:
        check("缺口3 订单列表非空", False, f"status={st} records=0 body={body}")
    else:
        r0 = recs[0]
        check(
            "缺口3 列表含 paymentStatus",
            "paymentStatus" in r0 and "paymentStatusLabel" in r0,
            f"id={r0.get('id')} paymentStatus={r0.get('paymentStatus')} label={r0.get('paymentStatusLabel')}",
        )
        check(
            "缺口3 列表含 actualFee 或 status 非结算态",
            "actualFee" in r0 or r0.get("paymentStatus") != "SETTLED",
            f"actualFee={r0.get('actualFee')} paymentStatus={r0.get('paymentStatus')}",
        )
        leaked = [k for k in FORBIDDEN_IN_LIST if k in r0]
        check(
            "缺口3 隐私红线未破（无内部 id/地址）",
            not leaked,
            f"泄露字段={leaked if leaked else '无'} 首条keys={sorted(r0.keys())}",
        )

        # 列表字段必须与详情接口一致（防止「加了字段但值不对」）
        st2, body2 = http("GET", f"/order/{r0.get('id')}", token=tokens["FAMILY"])
        d = body2.get("data") or {}
        check(
            "缺口3 列表值 == 详情值",
            d.get("paymentStatus") == r0.get("paymentStatus") and d.get("actualFee") == r0.get("actualFee"),
            f"list({r0.get('paymentStatus')},{r0.get('actualFee')}) vs detail({d.get('paymentStatus')},{d.get('actualFee')})",
        )

    # ---------- 落盘 ----------
    out = os.path.join("reports", "e2e", "backend-gap-probe.txt")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    failed = [r for r in results if not r[1]]
    with open(out, "w", encoding="utf-8") as f:
        f.write("# 后端三处缺口 · 真机 HTTP 探针\n\n")
        for name, ok, detail in results:
            f.write(f"[{'OK  ' if ok else 'FAIL'}] {name:<38} {detail}\n")
        f.write(f"\n合计 {len(results)} 项，通过 {len(results) - len(failed)}，失败 {len(failed)}\n")
        if uploaded_url:
            f.write(f"\n本次上传产物 url = {uploaded_url}（需人工清理 sys_file 行与磁盘文件）\n")

    print(f"\n合计 {len(results)} 项，通过 {len(results) - len(failed)}，失败 {len(failed)}")
    print(f"写出 {out}")
    if uploaded_url:
        print(f"注意：本次真实上传了 1 个文件 → {uploaded_url}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
