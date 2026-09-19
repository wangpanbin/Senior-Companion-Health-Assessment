# -*- coding: utf-8 -*-
"""
静态校验：frontend/src 下所有 `import { ... } from '@/api/xxx'` 的具名导入
是否真的被目标模块导出。

为什么要单独写这个：`getProfile` 从 '@/api/auth' 导入这件事，
**Vite 只会在运行时炸一个 SyntaxError**，构建不做静态校验（本项目又不跑 build），
所以只能靠 Playwright 逐页跑到才发现。这个脚本 3 秒扫完，比巡检快两个数量级。
"""
import io
import os
import re
import sys

ROOT = r"F:\test\Senior Companion Health Assessment\frontend\src"
API_DIR = os.path.join(ROOT, "api")

IMPORT_RE = re.compile(
    r"import\s*\{([^}]*)\}\s*from\s*['\"]@/api/([A-Za-z0-9_\-]+)['\"]", re.S
)
EXPORT_RE = re.compile(
    r"^\s*export\s+(?:async\s+)?(?:function|const|let|class)\s+([A-Za-z0-9_$]+)", re.M
)
DEFAULT_RE = re.compile(r"^\s*export\s+default\s", re.M)


def exports_of(mod):
    path = os.path.join(API_DIR, mod + ".js")
    if not os.path.exists(path):
        return None
    with io.open(path, "r", encoding="utf-8") as fh:
        src = fh.read()
    return set(EXPORT_RE.findall(src))


def main():
    problems = []
    checked = 0
    for dirpath, _dirs, files in os.walk(ROOT):
        for fn in files:
            if not fn.endswith(".vue") and not fn.endswith(".js"):
                continue
            path = os.path.join(dirpath, fn)
            rel = os.path.relpath(path, ROOT).replace("\\", "/")
            if rel.startswith("api/"):
                pass  # api 层自身也扫，能发现互相引用的错
            with io.open(path, "r", encoding="utf-8", errors="replace") as fh:
                lines = fh.readlines()
            for lineno, line in enumerate(lines, 1):
                m = IMPORT_RE.search(line)
                if not m:
                    continue
                names = [n.strip().split(" as ")[0].strip()
                         for n in m.group(1).split(",") if n.strip()]
                exps = exports_of(m.group(2))
                if exps is None:
                    problems.append("%s:%d 引用的模块 @/api/%s 不存在" % (rel, lineno, m.group(2)))
                    continue
                for n in names:
                    checked += 1
                    if n not in exps:
                        problems.append(
                            "%s:%d  @/api/%s 没有导出 `%s`（该模块导出：%s）"
                            % (rel, lineno, m.group(2), n, ", ".join(sorted(exps)) or "无")
                        )
    print("检查具名导入 %d 处" % checked)
    if problems:
        print("\n发现 %d 个不存在的导入：\n" % len(problems))
        for p in problems:
            print("  " + p)
        return 1
    print("全部通过：没有指向不存在导出的 import。")
    return 0


sys.exit(main())
