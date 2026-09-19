#!/usr/bin/env bash
# 银龄伴诊 · 把数据库 / Redis 恢复到「干净基线」
#
# 适用场景：E2E 写路径测试（Playwright / 压测）污染了种子数据。
# 详见 reports/playwright/e2e-report.md §F-04 与 docs/agents/BUG_LIST.md L1。
#
# ⚠️ 破坏性操作：默认 dry-run；必须显式传 --execute 才会真删。
#
# 用法：
#   # 1) 看计划（推荐先做）
#   ./tools/e2e/restore_clean_baseline.sh
#
#   # 2) 真执行 —— 会二次确认
#   ./tools/e2e/restore_clean_baseline.sh --execute
#
#   # 3) 跳过二次确认（CI 用，自己负责）
#   ./tools/e2e/restore_clean_baseline.sh --execute --yes
#
# DB 口令走 MYSQL_PASSWORD 环境变量（**脚本不内置默认口令**，明文口令不入库）；
# Redis CLI 用 REDIS_CLI 或默认 redis-cli。

set -euo pipefail

PROJ="$(cd "$(dirname "$0")/../.." && pwd)"
if [ -z "${MYSQL_PASSWORD:-}" ]; then
  echo "错误：未设置 MYSQL_PASSWORD（本机 dev 口令）。脚本不内置默认口令，请先导出后重试。" >&2
  exit 1
fi
export MYSQL_PASSWORD

# 17 张被写路径触动的表（与 FIXTURE_ROLLBACK_PLAN §3 一致）
TABLES=(
  companion_order
  order_checkin
  companion_track
  order_review
  complaint
  medication_task
  medication_plan
  internal_message
  sys_login_log
  admin_oper_log
  companion_audit_record
  medication_log
  execution_photo
  elder_emergency_contact
  family_elder_relation
  companion_profile
  sys_user
)

# 种子基线水位
declare -A SEED_MAX_IDS=(
  [internal_message]=40072
)

EXECUTE=0
YES=0
for arg in "$@"; do
  case "$arg" in
    --execute) EXECUTE=1 ;;
    --yes)     YES=1 ;;
    -h|--help)
      sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "未知参数: $arg"; exit 2 ;;
  esac
done

echo "== 恢复基线 · 计划 =="
echo "项目根: $PROJ"
echo "数据库: 127.0.0.1:3306 / nianglin"
echo

for t in "${TABLES[@]}"; do
  if [[ -n "${SEED_MAX_IDS[$t]:-}" ]]; then
    max="${SEED_MAX_IDS[$t]}"
  else
    max="(MAX(id) - 100)"
  fi
  printf "%-30s  MAX(id) keep <= %s\n" "$t" "$max"
done

if [[ "$EXECUTE" -eq 0 ]]; then
  echo
  echo "⚠️ 当前是 dry-run 模式，**未执行任何 SQL**。"
  echo "   确认无误后请加 --execute 参数再次运行。"
  exit 0
fi

# 二次确认
if [[ "$YES" -eq 0 ]]; then
  echo
  echo -e "\033[31m即将 DELETE 多张表的写路径新增行，此操作不可逆。\033[0m"
  read -rp "确认继续？(输入 yes 继续，其它任意键退出): " ans
  if [[ "$ans" != "yes" ]]; then
    echo "已取消。" >&2
    exit 1
  fi
fi

mysql_exec() {
  mysql -h 127.0.0.1 -P 3306 -u root -p"$MYSQL_PASSWORD" -N -B "$@"
}

echo
echo "== 执行 =="
for t in "${TABLES[@]}"; do
  if [[ -n "${SEED_MAX_IDS[$t]:-}" ]]; then
    max="${SEED_MAX_IDS[$t]}"
  else
    cur_max=$(mysql_exec -e "SELECT IFNULL(MAX(id),0) FROM nianglin.$t")
    max=$((cur_max - 100))
    [[ "$max" -lt 0 ]] && max=0
  fi
  before=$(mysql_exec -e "SELECT IFNULL(MAX(id),0) FROM nianglin.$t")
  affected=$(mysql_exec -e "DELETE FROM nianglin.$t WHERE id > $max;" 2>&1 | tail -1)
  printf "%-30s  MAX(id) before=%-10s  affected=%s\n" "$t" "$before" "$affected"
done

echo
echo "== Redis 清理 =="
REDIS_CLI_BIN="${REDIS_CLI:-redis-cli}"
for pattern in 'order:seq:*' 'pwd:version:*'; do
  count=$("$REDIS_CLI_BIN" --scan --pattern "$pattern" | wc -l | tr -d ' ')
  if [[ "$count" -gt 0 ]]; then
    "$REDIS_CLI_BIN" --scan --pattern "$pattern" | xargs -r "$REDIS_CLI_BIN" DEL > /dev/null
    echo "DEL $count keys matching $pattern"
  else
    echo "$pattern : (无 key)"
  fi
done

echo
echo -e "\033[32m✅ 完成。建议跑一遍：\033[0m"
echo "   cd frontend && pnpm exec playwright test --grep \"数据回滚\""
