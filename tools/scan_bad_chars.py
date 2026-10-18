"""扫描仓库内本轮新增/修改的源码文件，找出 U+FFFD 损坏字符并报告所在行。

用法：python tools/scan_bad_chars.py [目录]
只读，不修改。
"""
import io
import os
import sys

root = sys.argv[1] if len(sys.argv) > 1 else "."

BAD = "\ufffd"
hits = 0
for dirpath, _dirnames, filenames in os.walk(root):
    if any(part in dirpath for part in (".git", "node_modules", "target", "dist")):
        continue
    for name in filenames:
        if not name.endswith((".java", ".vue", ".js", ".md", ".sql", ".yml", ".scss")):
            continue
        p = os.path.join(dirpath, name)
        try:
            s = io.open(p, encoding="utf-8", errors="replace").read()
        except OSError:
            continue
        if BAD not in s:
            continue
        for i, line in enumerate(s.split("\n"), 1):
            if BAD in line:
                hits += 1
                print("%s:%d: %s" % (p, i, line.strip()))
print("--- 共 %d 处 ---" % hits)
