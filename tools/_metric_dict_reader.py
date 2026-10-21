# -*- coding: utf-8 -*-
"""指标字典机器面的**极小**读取器（只读，不写）。

为什么不用 PyYAML
-----------------
CI 的 job 里没有承诺装 PyYAML，而 `.github/workflows/ci.yml` 顶部明写
「不要引入 pom.xml / package.json 之外的依赖」。所以本仓库的字典校验脚本
一律只用标准库。

所以这里**不是**通用 YAML 解析器 —— 它只认指标字典那一份固定形状：

    metrics:
      - key: xxx
        name: ...
        scope: ...
        period: ...
        source: ...
        formula: ...
        caliberVersion: 1
        endpoints:      ← 嵌套列表，本模块不关心
        bindings:       ← 嵌套列表，本模块不关心

**换一种缩进或换一种写法就该报错，而不是猜。**
真需要完整 YAML 能力时，去 Java 侧 —— `MetricDictionary` 用的就是
Spring 自带的 SnakeYAML，那条路才是有依赖保障的。
"""
import io
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
YML = ROOT / "backend/src/main/resources/metrics/metric-dictionary.yml"

# metrics 段里的标量字段
WANTED_FIELDS = ("name", "scope", "period", "source", "formula", "caliberVersion")

_KEY_PATTERN = re.compile(r"^  - key:\s*(?P<key>\S+)\s*$")
_FIELD_PATTERN = re.compile(r"^    (?P<field>[A-Za-z]+):\s*(?P<value>.*?)\s*$")


def _unquote(value):
    """去掉 YAML 的双引号包裹；其余原样返回。"""
    if len(value) >= 2 and value[0] == '"' and value[-1] == '"':
        return value[1:-1]
    return value


def load_metrics(path=None):
    """读出 metrics 段的标量字段。

    :return: ``{key: {field: value}}``，字段见 :data:`WANTED_FIELDS`；
             每条记录里也带一个 ``key`` 字段，方便 ``values()`` 之后直接取用
    """
    yml = Path(path) if path else YML
    metrics = {}
    current = None
    in_metrics = False
    for raw in io.open(yml, encoding="utf-8"):
        line = raw.rstrip("\n").rstrip("\r")
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if not line.startswith(" "):
            # 顶层键：metrics / vos / version ...
            in_metrics = line.startswith("metrics:")
            current = None
            continue
        if not in_metrics:
            continue
        m = _KEY_PATTERN.match(line)
        if m:
            current = m.group("key")
            # 把 key 也放进记录里，调用方可以 `record["key"]` 直接取用
            metrics[current] = {"key": current}
            continue
        if current is None:
            continue
        m = _FIELD_PATTERN.match(line)
        if m and m.group("field") in WANTED_FIELDS:
            metrics[current][m.group("field")] = _unquote(m.group("value"))
    if not metrics:
        raise SystemExit("机器面解析失败：{} 里一个指标都没读到（形状变了？）".format(yml))
    return metrics


def mentioned_columns(source):
    """取 source 里括号中提到的字段名集合。

    例：``companion_order(status, create_time)`` → ``{status, create_time}``
    例：``complaint + companion_order``            → 空集（没提到任何字段）
    例：``companion_order(..., payment_status)``   → ``{payment_status}``

    只取**最后一层**括号里的内容：机器面偶尔会写
    ``匹配规则引擎（companion_profile / companion_order / ...）``，
    那是表名不是字段，用斜杠分隔，取不到字段名正合适。
    """
    cols = set()
    for group in re.findall(r"\(([^()]*)\)", source or ""):
        for token in re.split(r"[,\s/]+", group):
            token = token.strip()
            if not token or token == "...":
                continue
            if re.fullmatch(r"[a-z][a-z0-9_]*", token):
                cols.add(token)
    return cols
