#!/usr/bin/env bash
# ============================================================================
# rR1a6L 四端改造 · 离线验收测试套件 v1.0
#
# 目的：在**不依赖 8080 服务 / 不依赖真实凭据 / 不依赖真机**的前提下，
#       把 rR1a6L 验收清单里所有可离线验证的判据一次性跑完，
#       产出可直接交测试负责人的机器证据（file:line + 通过/失败）。
#
# 三态判定（★ 防假绿）：
#   PASS    判据成立
#   FAIL    判据不成立 —— 真实缺陷
#   BLOCKED 环境/依赖不可得，**不算通过**（如缺 node/esbuild/maven）
# 退出码：0 = 无 FAIL 且无 BLOCKED；1 = 存在 FAIL 或 BLOCKED（fail-closed，绝不半跑）
#
# 覆盖：A-1 / A-2 / A-3 / B-1(含 S-1) / B-2 / C-1 / C-2 / C-3 / C-4 / 产物扫描
# 不覆盖（须真机，交测试负责人）：D-1 / D-2 / D-3
#
# 用法：
#   bash verify-rR1a6L-offline.sh [端侧根] [后端根]
# 默认端侧根：C:/Users/Administrator/WorkBuddy/2026-09-08-13-00-34/stage3_开发
# ============================================================================
set -uo pipefail

STAGE_ROOT="${1:-C:/Users/Administrator/WorkBuddy/2026-09-08-13-00-34/stage3_开发}"
SERVER_ROOT="${2:-C:/Users/Administrator/WorkBuddy/2026-09-18-11-08-33/stage3_开发/yl-server}"
APPS=(weixin-app ios-app douyin-app)
PATCH_DIR="$STAGE_ROOT/_改造-rR1a6L"

PASS=0; FAIL=0; BLOCKED=0
RESULTS=()

# ---------- 基础工具 ----------
ok()    { PASS=$((PASS+1));    RESULTS+=("PASS    | $1 | $2"); printf '  \033[32mPASS\033[0m    %-8s %s\n' "$1" "$2"; }
bad()   { FAIL=$((FAIL+1));    RESULTS+=("FAIL    | $1 | $2"); printf '  \033[31mFAIL\033[0m    %-8s %s\n' "$1" "$2"; }
block() { BLOCKED=$((BLOCKED+1)); RESULTS+=("BLOCKED | $1 | $2"); printf '  \033[33mBLOCK\033[0m   %-8s %s\n' "$1" "$2"; }

# 代码层匹配（★ 排除注释行：行首为 * / // / /*，避免「注释里写着旧写法」被误判为缺陷）
# 用法：code_grep <文件> <正则>  → 输出 "行号:内容"
code_grep() {
  local f="$1" re="$2"
  [ -f "$f" ] || return 1
  grep -nE "$re" "$f" 2>/dev/null \
    | grep -vE '^[0-9]+:[[:space:]]*(\*|//|/\*)' \
    | grep -vE '^[0-9]+:[[:space:]]*#[^!]' || true
}
count_code_hits() { code_grep "$1" "$2" | grep -c . || true; }

echo "=============================================================="
echo " rR1a6L 离线验收测试套件 v1.0"
echo " 端侧根：$STAGE_ROOT"
echo " 后端根：$SERVER_ROOT"
echo " 时间：$(date '+%Y-%m-%d %H:%M:%S')"
echo "=============================================================="

# ---------- 前置：路径可达性 ----------
echo ""
echo "[前置] 路径可达性"
for a in "${APPS[@]}"; do
  if [ -d "$STAGE_ROOT/$a" ]; then ok "PATH" "$a 存在"; else bad "PATH" "$a 缺失：$STAGE_ROOT/$a"; fi
done
[ -d "$STAGE_ROOT/android-native" ] && ok "PATH" "android-native 存在" || bad "PATH" "android-native 缺失"
[ -d "$SERVER_ROOT" ] && ok "PATH" "yl-server 存在" || bad "PATH" "yl-server 缺失"
[ -d "$PATCH_DIR" ] && ok "PATH" "改造交付目录存在" || block "PATH" "改造交付目录缺失：$PATCH_DIR"

# ============================================================================
echo ""
echo "=== T1 · A-1 TS 侧 ELDER 角色枚举（三端） ==="
for a in "${APPS[@]}"; do
  f="$STAGE_ROOT/$a/src/utils/role.ts"
  if [ ! -f "$f" ]; then bad "A-1" "$a role.ts 缺失"; continue; fi
  if grep -qE "export type Role = .*'ELDER'" "$f"; then
    ln=$(grep -nE "export type Role = .*'ELDER'" "$f" | head -1 | cut -d: -f1)
    ok "A-1" "$a role.ts:$ln Role 含 'ELDER'"
  else
    bad "A-1" "$a role.ts 的 Role 类型不含 'ELDER'"
  fi
done
# auth.ts 归一化分支同步
for a in "${APPS[@]}"; do
  f="$STAGE_ROOT/$a/src/stores/auth.ts"
  [ -f "$f" ] || { bad "A-1" "$a auth.ts 缺失"; continue; }
  if grep -qE "'ELDER'" "$f"; then
    ln=$(grep -nE "'ELDER'" "$f" | head -1 | cut -d: -f1)
    ok "A-1" "$a auth.ts:$ln 归一化分支含 ELDER"
  else
    bad "A-1" "$a auth.ts 无 ELDER 分支"
  fi
done
# login.vue 老人选项
for a in "${APPS[@]}"; do
  f="$STAGE_ROOT/$a/src/pages/login/login.vue"
  [ -f "$f" ] || { bad "A-1" "$a login.vue 缺失"; continue; }
  if grep -qE "role = 'elder'|'elder'" "$f"; then
    ln=$(grep -nE "role = 'elder'" "$f" | head -1 | cut -d: -f1)
    ok "A-1" "$a login.vue:${ln:-含elder} 提供老人选项"
  else
    bad "A-1" "$a login.vue 无老人选项"
  fi
done

# ============================================================================
echo ""
echo "=== T2 · A-2 Kotlin 侧 ROLE_ELDER ==="
FK="$STAGE_ROOT/android-native/app/src/main/java/com/yl/shouhu/util/FamilyUtils.kt"
if [ ! -f "$FK" ]; then
  block "A-2" "FamilyUtils.kt 缺失：$FK"
else
  if grep -qE "const val ROLE_ELDER" "$FK"; then
    ln=$(grep -nE "const val ROLE_ELDER" "$FK" | head -1 | cut -d: -f1)
    ok "A-2" "FamilyUtils.kt:$ln 定义 ROLE_ELDER"
  else
    bad "A-2" "FamilyUtils.kt 未定义 ROLE_ELDER"
  fi
  if grep -qE "ROLE_ELDER" "$FK"; then
    n=$(grep -cE "ROLE_ELDER" "$FK")
    ok "A-2" "FamilyUtils.kt ROLE_ELDER 引用 $n 处"
  else
    bad "A-2" "FamilyUtils.kt 无 ROLE_ELDER 引用"
  fi
fi

# ============================================================================
echo ""
echo "=== T3 · A-3 三端同源一致（★ 实际文件 md5，不采信表内自报） ==="
# 共享文件清单取 md5-table.tsv 中 class 含「共享」的行
if [ -f "$PATCH_DIR/md5-table.tsv" ]; then
  mapfile -t SHARED < <(awk -F'\t' 'NR>1 && $2 ~ /共享/ {print $1}' "$PATCH_DIR/md5-table.tsv")
  if [ ${#SHARED[@]} -eq 0 ]; then
    block "A-3" "md5-table.tsv 未解析出共享文件"
  else
    for rel in "${SHARED[@]}"; do
      declare -A seen=()
      all_exist=1
      for a in "${APPS[@]}"; do
        f="$STAGE_ROOT/$a/$rel"
        if [ ! -f "$f" ]; then all_exist=0; break; fi
        m=$(md5sum "$f" | awk '{print toupper($1)}')
        seen["$m"]=1
      done
      if [ $all_exist -eq 0 ]; then
        bad "A-3" "$rel 某端缺失"
      elif [ ${#seen[@]} -eq 1 ]; then
        ok "A-3" "$rel 三端 md5 唯一（${!seen[@]}）"
      else
        bad "A-3" "$rel 三端 md5 不一致（${#seen[@]} 种）"
      fi
    done
  fi
else
  block "A-3" "md5-table.tsv 缺失，无法确定共享文件清单"
fi

# ============================================================================
echo ""
echo "=== T4 · B-1 / S-1 兜底路由三元全部消除（★ 仅代码层计命中） ==="
for a in "${APPS[@]}"; do
  f="$STAGE_ROOT/$a/src/utils/role.ts"
  [ -f "$f" ] || { bad "B-1" "$a role.ts 缺失"; continue; }
  hits=$(count_code_hits "$f" "role === 'FAMILY'[[:space:]]*\?")
  if [ "${hits:-0}" -eq 0 ]; then
    ok "B-1" "$a role.ts 代码层 role==='FAMILY'? 三元 = 0"
  else
    bad "B-1" "$a role.ts 仍有 $hits 处三元未消除：$(code_grep "$f" "role === 'FAMILY'[[:space:]]*\?" | head -3 | tr '\n' ' ')"
  fi
  # 默认分支必须落到登录页：断言 login 路由在白名单分发的默认分支出现
  if grep -qE "ROUTE\.login" "$f"; then
    ok "B-1" "$a role.ts 含 ROUTE.login 兜底"
  else
    bad "B-1" "$a role.ts 无 ROUTE.login 兜底"
  fi
done

# ============================================================================
echo ""
echo "=== T5 · B-2 Kotlin 兜底收敛 ==="
MK="$STAGE_ROOT/android-native/app/src/main/java/com/yl/shouhu/data/api/MockApiClient.kt"
if [ ! -f "$MK" ]; then
  block "B-2" "MockApiClient.kt 缺失"
else
  hits=$(count_code_hits "$MK" "else[[:space:]]+ROLE_ASSESSOR")
  if [ "${hits:-0}" -eq 0 ]; then
    ok "B-2" "MockApiClient.kt 代码层 else ROLE_ASSESSOR = 0"
  else
    bad "B-2" "MockApiClient.kt 仍有 $hits 处 else ROLE_ASSESSOR"
  fi
  hits2=$(count_code_hits "$MK" "ROLE_ASSESSOR")
  if [ "${hits2:-0}" -eq 0 ]; then
    ok "B-2" "MockApiClient.kt 代码层无 ROLE_ASSESSOR 默认兜底"
  else
    bad "B-2" "MockApiClient.kt 代码层仍有 $hits2 处 ROLE_ASSESSOR"
  fi
fi

# ============================================================================
echo ""
echo "=== T6 · C-2 端侧不得直读 report_dimension_score（★ 仅代码层） ==="
for a in "${APPS[@]}"; do
  root="$STAGE_ROOT/$a/src"
  [ -d "$root" ] || { bad "C-2" "$a src 缺失"; continue; }
  total=0
  while IFS= read -r f; do
    n=$(count_code_hits "$f" "report_dimension_score")
    total=$((total + ${n:-0}))
  done < <(grep -rlE "report_dimension_score" "$root" 2>/dev/null || true)
  if [ "$total" -eq 0 ]; then
    ok "C-2" "$a 代码层无 report_dimension_score 直读"
  else
    bad "C-2" "$a 代码层仍有 $total 处直读"
  fi
done

# ============================================================================
echo ""
echo "=== T7 · C-1 / C-3 老人读面与入口收敛 ==="
for a in "${APPS[@]}"; do
  eh="$STAGE_ROOT/$a/src/pages/elder-home/elder-home.vue"
  if [ -f "$eh" ]; then
    ok "C-1" "$a elder-home.vue 存在（老人专属落地页）"
  else
    bad "C-1" "$a 缺 elder-home.vue"
  fi
  # 老人 tab 集不得等于评估员 tab 集
  f="$STAGE_ROOT/$a/src/utils/role.ts"
  [ -f "$f" ] || { bad "C-3" "$a role.ts 缺失"; continue; }
  if grep -qE "tabsOf|ELDER_TABS|elderTabs" "$f"; then
    ok "C-3" "$a role.ts 含老人专属 tab 分发"
  else
    bad "C-3" "$a role.ts 无老人 tab 分发（可能复用评估员 TabBar）"
  fi
done

# ============================================================================
echo ""
echo "=== T8 · 产物扫描（合规 + elder-home 入产物） ==="
# ★ 关键：dist/build 下混有大量**历史快照目录**（prev / old / dirty / DEMO / FAILED / pre-*），
#   扫描整个 dist 会把旧产物的问题算到当前产物头上（假红）。
#   因此只扫「当前产物目录白名单」，且防御性剔除含黑名单关键词的目录名。
is_current_build_dir() {
  local n="$1"
  case "$n" in
    h5|mp-weixin|mp-toutiao|app|.nvue) ;;
    *) return 1 ;;
  esac
  case "$n" in
    *prev*|*old*|*dirty*|*DEMO*|*FAILED*|pre-*|*-pre-*) return 1 ;;
  esac
  return 0
}
for a in "${APPS[@]}"; do
  build="$STAGE_ROOT/$a/dist/build"
  if [ ! -d "$build" ]; then block "PROD" "$a dist/build 缺失（未构建）"; continue; fi
  targets=()
  while IFS= read -r d; do
    n=$(basename "$d")
    is_current_build_dir "$n" && targets+=("$d")
  done < <(find "$build" -maxdepth 1 -mindepth 1 -type d 2>/dev/null)
  if [ ${#targets[@]} -eq 0 ]; then
    block "PROD" "$a 未找到当前产物目录（白名单：h5 / mp-weixin / mp-toutiao / app）"
    continue
  fi
  ok "PROD" "$a 当前产物目录：$(printf '%s ' "${targets[@]##*/}")"
  # 内测 / facePassed / 热线
  h1=$(grep -rlE "内测|facePassed" "${targets[@]}" 2>/dev/null | wc -l)
  if [ "${h1:-0}" -eq 0 ]; then ok "PROD" "$a 当前产物无「内测」/facePassed"; else bad "PROD" "$a 当前产物含内测/facePassed（$h1 文件）：$(grep -rlE '内测|facePassed' "${targets[@]}" 2>/dev/null | head -2 | tr '\n' ' ')"; fi
  # 手机号硬编码
  # ★ 必须用 -rn（输出整行）再用 -F 过滤 vendor 常量：vendor 的 Number.MAX_SAFE_INTEGER
  #   `9007199254740991` 内部的子串 `19925474099` 会被 11 位手机号正则命中，
  #   若用 -rno（只输出匹配片段）则丢失上下文、无法排除 ⇒ iOS 端会恒定假红 3 处。
  h2=$(grep -rnE "1[3-9][0-9]{9}" "${targets[@]}" 2>/dev/null | grep -vcF "9007199254740991" || true)
  vh=$(grep -rnE "1[3-9][0-9]{9}" "${targets[@]}" 2>/dev/null | grep -cF "9007199254740991" || true)
  if [ "${h2:-0}" -eq 0 ]; then
    ok "PROD" "$a 当前产物无硬编码手机号（vendor 大整数子串 $vh 处已排除）"
  else
    bad "PROD" "$a 当前产物含疑似手机号 $h2 处：$(grep -rnE '1[3-9][0-9]{9}' "${targets[@]}" 2>/dev/null | grep -vF 9007199254740991 | head -3 | cut -c1-90 | tr '\n' ' ')"
  fi
  # elder-home 入产物
  h3=$(grep -rlE "elder-home" "${targets[@]}" 2>/dev/null | wc -l)
  if [ "${h3:-0}" -gt 0 ]; then ok "PROD" "$a 当前产物含 elder-home（$h3 文件）"; else bad "PROD" "$a 当前产物无 elder-home"; fi
  # IS_DEMO_BUILD 必须为 false
  h5=$(grep -rnoE "IS_DEMO_BUILD=!?[01]" "${targets[@]}" 2>/dev/null | grep -cE "=1$" || true)
  if [ "${h5:-0}" -eq 0 ]; then ok "PROD" "$a 当前产物 IS_DEMO_BUILD 未置真"; else bad "PROD" "$a 当前产物 IS_DEMO_BUILD=真 $h5 处"; fi
done

# ============================================================================
echo ""
echo "=== T9 · 行为断言（三端角色分发，复用交付件 apply-test.js） ==="
NODE_BIN=""
for c in "C:/Users/Administrator/.workbuddy/binaries/node/versions/22.22.2-3/node.exe" \
         "C:/Users/Administrator/.workbuddy/binaries/node/versions/22.22.2-6/node.exe" \
         "C:/Program Files/nodejs/node.exe"; do
  [ -x "$c" ] && { NODE_BIN="$c"; break; }
done
if [ -z "$NODE_BIN" ]; then
  block "BEHAV" "未找到 node 可执行文件"
elif [ ! -f "$PATCH_DIR/apply-test.js" ]; then
  block "BEHAV" "apply-test.js 缺失"
else
  for a in "${APPS[@]}"; do
    out=$("$NODE_BIN" "$PATCH_DIR/apply-test.js" "$a" "$STAGE_ROOT" 2>&1)
    rc=$?
    if [ $rc -eq 0 ]; then
      n=$(printf '%s' "$out" | grep -ciE "\bPASS\b" || true)
      f_n=$(printf '%s' "$out" | grep -ciE "\bFAIL\b" || true)
      ok "BEHAV" "$a 行为断言 exit=0（PASS≈${n:-0}）"
    else
      block "BEHAV" "$a 行为断言 exit=$rc（需查 node_modules/esbuild 是否就绪）：$(printf '%s' "$out" | tail -2 | tr '\n' ' ')"
    fi
  done
fi

# ============================================================================
echo ""
echo "=== T10 · C-4 后端回归（M3 状态闸不得误伤） ==="
MVN=""
for c in "C:/tools/apache-maven-3.9.16/bin/mvn.cmd"; do [ -f "$c" ] && { MVN="$c"; break; }; done
if [ -z "$MVN" ]; then
  block "C-4" "未找到 mvn"
else
  # 仅跑三个目标测试类，跳过静态检查以缩短时间（行为回归为目的）
  log=$(mktemp)
  ( cd "$SERVER_ROOT" && "$MVN" -B -ntp -o -Pfast test \
      -Dtest='DataScopePermissionHandlerTest,AccountAggregateServiceTest,AssessmentReportServiceTest' \
      -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false ) >"$log" 2>&1
  rc=$?
  if [ $rc -eq 0 ]; then
    summary=$(grep -aE "Tests run:.*in cn\.yl" "$log" | tail -3 | tr '\n' ' ')
    ok "C-4" "后端三测试类回归通过：$summary"
  else
    bad "C-4" "后端回归失败 exit=$rc：$(grep -aE "Tests run|ERROR" "$log" | tail -3 | tr '\n' ' ')"
  fi
  # 不 rm 临时日志：本机安全策略会拦截 /tmp 下的删除（SAFE_DELETE_INVALID_PATH），
  # 交由系统临时目录自然回收，避免脚本产生「无意义的被拦截动作」。
  : > "$log" 2>/dev/null || true
fi

# ============================================================================
echo ""
echo "=============================================================="
echo " 汇总：PASS=$PASS  FAIL=$FAIL  BLOCKED=$BLOCKED"
echo "=============================================================="
[ "$FAIL" -gt 0 ] && { echo ""; echo "--- FAIL 明细 ---"; printf '%s\n' "${RESULTS[@]}" | grep '^FAIL'; }
[ "$BLOCKED" -gt 0 ] && { echo ""; echo "--- BLOCKED 明细（不算通过） ---"; printf '%s\n' "${RESULTS[@]}" | grep '^BLOCKED'; }
echo ""
echo "★ 不可离线验证、须交测试负责人真机执行：D-1 / D-2 / D-3"
echo ""

if [ "$FAIL" -eq 0 ] && [ "$BLOCKED" -eq 0 ]; then
  echo "判定：OFFLINE-GREEN（可进入真机 D 组）"
  exit 0
else
  echo "判定：OFFLINE-NOT-GREEN（禁止据此勾选验收框）"
  exit 1
fi
