#!/usr/bin/env bash
# ============================================================================
# rollback-cr-m2-009-2008.sh —— CR-M2-009 事项1（2008 写权限）否决/逾期回滚脚本
# ============================================================================
# 依据：docs/change/CR-M2-009-ccb-ratification-2008-prd-writeback.md §6.1（唯一可回滚范围）
# 触发条件（二选一，均须 PM 裁决留痕后才能执行）：
#   A. CCB 否决事项 1；
#   B. 2026-10-17 会签未齐，PM 逾期裁决选「方案 A：回滚 2008 授权」。
#
# ★★ 红线（CR-M2-009 §二 / D4-R7 卡 §11）★★
#   授权 (3041,1001,2008) 与 eval_report 归属锚强耦合：
#   - 本脚本只删授权 + 回退文档/契约/断言（安全方向）；
#   - 若还要回退 R8 读锚（care_plan 迁出 SELF_VIA_PARENT），必须在本脚本之后
#     作为独立代码变更单独评审——**任何涉及锚的回退都必须先完成本脚本**。
#
# 用法：
#   bash scripts/rollback-cr-m2-009-2008.sh                 # dry-run：只打印动作，不改任何东西
#   EXECUTE=1 bash scripts/rollback-cr-m2-009-2008.sh       # 真实执行
#
# 环境变量：
#   MYSQL_BIN      mysql 客户端路径（默认 mysql）
#   MYSQL_CONN     mysql 连接参数串（如 "-h127.0.0.1 -uroot -pXXXX"，凭据走文件，禁内联进脚本）
#   DB_NAME        库名（默认 yl_evaluation）
#
# 执行内容（= CR-M2-009 §6.1 四项）：
#   1. DB 删除种子授权一行 (3041,1001,2008) + 种子文件同步删行
#   2. scripts/verify-schema.sh 授权断言 41 → 40
#   3. B2 三文档「老人补授」标记回退（git 反打 86fea0d 中这三个文件的改动）
#   4. openapi/yl-api.yaml care-plan POST 的 ELDER: self + via 声明回退（同上反打）
#
# 必须保留（本脚本绝不触碰，红线见 §6.2）：R7 锚 / R8 读路径 / F2 / M3 状态闸及其测试。
set -u

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

EXECUTE=${EXECUTE:-0}
SEED_FILE="docker/mysql/init/03_seed_rbac.sql"
SCHEMA_SCRIPT="scripts/verify-schema.sh"
# 86fea0d = D4-R7 R7+R8+F2+2008 同批落地提交；其在这 6 个文件内的改动恰为 2008 写权限面
#（种子文件在该提交内只有 (3041,1001,2008) 的 INSERT 块，已实查）
GRANT_COMMIT="86fea0d"
PATCH_FILES="docker/mysql/init/03_seed_rbac.sql docs/design/B2-permission-point-catalog.md docs/design/B2-permission-api-matrix.md docs/design/B2-account-rbac-design.md openapi/yl-api.yaml scripts/verify-schema.sh"

echo "== [0/4] 前置自检 =="
if [ "$EXECUTE" != "1" ]; then
    echo "  模式：DRY-RUN（只检查与打印，不落任何动作；EXECUTE=1 才真实执行）"
fi
# 提交指纹校验：防止 GRANT_COMMIT 被重写后反打错内容
if ! git merge-base --is-ancestor "$GRANT_COMMIT" HEAD 2>/dev/null; then
    echo "FATAL: $GRANT_COMMIT 不在当前历史中，反打基线失效，拒绝执行" >&2; exit 1
fi
for f in $PATCH_FILES; do [ -f "$f" ] || { echo "FATAL: 缺文件 $f" >&2; exit 1; }; done

# 反打补丁生成 + 可应用性预检（本脚本唯一回退 docs/契约/断言的机制）
# ★ 补丁必须经临时文件传输：shell 变量会吃尾部换行 → hunk 截断 → corrupt patch
PATCH_FILE="$(mktemp)"
git diff "$GRANT_COMMIT"^ "$GRANT_COMMIT" -- $PATCH_FILES > "$PATCH_FILE"
[ -s "$PATCH_FILE" ] || { echo "FATAL: $GRANT_COMMIT 在指定文件上无改动，反打内容为空" >&2; rm -f "$PATCH_FILE"; exit 1; }
if ! git apply --reverse --check "$PATCH_FILE" 2>/tmp/rb-apply-check.err; then
    echo "FATAL: 反打补丁与当前工作区不匹配（文件可能已被后续提交改动），拒绝盲打。诊断：" >&2
    cat /tmp/rb-apply-check.err >&2; rm -f "$PATCH_FILE"; exit 1
fi
echo "  反打补丁预检通过（$GRANT_COMMIT 限定 6 文件，$(grep -c '^diff' "$PATCH_FILE") 个 diff）"

SEED_HITS=$(grep -c "(3041, 1001, 2008)" "$SEED_FILE" || true)
if [ "$SEED_HITS" != "1" ]; then
    echo "FATAL: 种子文件中 (3041, 1001, 2008) 出现 $SEED_HITS 次（预期 1），拒绝盲删" >&2; exit 1
fi
CUR_ASSERT=$(grep 'sys_role_permission' "$SCHEMA_SCRIPT" | grep -oE '"[0-9]+"$' | tr -d '"' | head -1)
if [ "$CUR_ASSERT" != "41" ]; then
    echo "FATAL: $SCHEMA_SCRIPT 当前授权断言 = ${CUR_ASSERT:-未知}（预期 41，否则说明状态已漂移），拒绝改写" >&2; exit 1
fi
echo "  种子行定位 1 处、断言 41 校验通过"

echo ""
echo "== [1/4] DB 删除种子授权 (3041,1001,2008) =="
if [ "$EXECUTE" = "1" ]; then
    : "${MYSQL_BIN:=mysql}"; : "${DB_NAME:=yl_evaluation}"
    [ -n "${MYSQL_CONN:-}" ] || { echo "FATAL: EXECUTE=1 需要 MYSQL_CONN（凭据走文件/环境，勿写进本脚本）" >&2; exit 1; }
    # shellcheck disable=SC2086
    "$MYSQL_BIN" $MYSQL_CONN "$DB_NAME" -e "DELETE FROM sys_role_permission WHERE id=3041 AND role_id=1001 AND perm_id=2008; SELECT COUNT(*) AS remain FROM sys_role_permission WHERE id=3041;"
    REMAIN=$("$MYSQL_BIN" $MYSQL_CONN "$DB_NAME" -N -B -e "SELECT COUNT(*) FROM sys_role_permission WHERE id=3041;")
    [ "$REMAIN" = "0" ] || { echo "FATAL: DB 删除后仍残留 $REMAIN 行，中止后续步骤" >&2; exit 1; }
    echo "  DB 行已删除（remain=0）"
else
    echo "  [dry-run] 将执行: DELETE FROM sys_role_permission WHERE id=3041 AND role_id=1001 AND perm_id=2008;"
fi

echo ""
echo "== [2/4] 种子文件删行 + 断言 41→40（随 [3/4] 反打补丁一并完成） =="
echo "  [包含于反打补丁：$SEED_FILE 删 (3041,1001,2008) INSERT 块；$SCHEMA_SCRIPT 断言与注释 41→40]"

echo ""
echo "== [3/4]+[4/4] 反打种子 + B2 三文档 + 契约 + verify-schema（$GRANT_COMMIT 限定 6 文件） =="
if [ "$EXECUTE" = "1" ]; then
    git apply --reverse "$PATCH_FILE" || { echo "FATAL: 反打失败（预检通过但应用失败，检查 git status）" >&2; exit 1; }
    echo "  已反打 5 文件"
else
    git apply --reverse --stat "$PATCH_FILE" | head -10
fi

echo ""
echo "== [5/5] 落盘后验证 =="
ERRS=0
if [ "$EXECUTE" = "1" ]; then
    grep -q "(3041, 1001, 2008)" "$SEED_FILE" && { echo "  ✗ 种子仍含 3041"; ERRS=1; } || echo "  ✓ 种子 3041 清零"
    grep -q '"41"' "$SCHEMA_SCRIPT" && { echo "  ✗ 断言仍为 41"; ERRS=1; } || echo "  ✓ 断言已回 40"
    grep -q "ELDER: self" openapi/yl-api.yaml && { echo "  ✗ 契约仍含 ELDER: self"; ERRS=1; } || echo "  ✓ 契约 ELDER: self 清零"
    # 红线复核：读侧修复必须原封不动
    grep -q "eval_report" yl-modules/yl-module-account/src/main/java/cn/yl/modules/account/security/DataScopePermissionHandler.java \
        && echo "  ✓ DataScopePermissionHandler 未被触碰（R7/R8 锚保留）" \
        || { echo "  ✗ 拦截器文件异常，立即检查！"; ERRS=1; }
    [ "$ERRS" = "0" ] || { echo "回滚后验证存在失败项，禁止提交" >&2; exit 1; }
    echo ""
    echo "回滚完成。后续必做："
    echo "  1) mvn -o verify + verify-schema.sh 全量回归（基线回到授权 40 形态）"
    echo "  2) 更新 CR-M2-009 会签栏结论与 D4-R7 卡 §11 状态"
    echo "  3) 若需回退 R8 读锚（care_plan）：独立代码变更，先删授权已完成，另行评审"
else
    echo "  [dry-run] 真实执行后将自动做上述 4 项验证"
fi
