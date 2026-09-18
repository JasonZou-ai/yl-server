#!/bin/sh
# =============================================================================
# 一键启用 / 管理仓库 Git 钩子
#
# 关联任务：B1-1 技术选型与工程脚手架（子任务 r0wpMZ 代码规范与静态检查）
# 决策依据：docs/ADR/0002-commit-convention-and-hooks.md
#
# 用法：
#   bash scripts/setup-hooks.sh              # 启用（设置 core.hooksPath=.githooks）
#   bash scripts/setup-hooks.sh --status      # 查看当前状态
#   bash scripts/setup-hooks.sh --uninstall   # 关闭钩子
#
# 说明：钩子脚本存放于 `.githooks/`（随仓库版本控制），通过 `core.hooksPath`
#       指向该目录，因此不需要往 `.git/hooks` 拷贝文件。
# =============================================================================

set -eu

cd "$(dirname "$0")/.."

HOOKS_DIR=".githooks"
MODE="${1:-enable}"

print_status() {
    CURRENT=$(git config --get core.hooksPath || true)
    echo "仓库：$(pwd)"
    if [ -n "$CURRENT" ]; then
        echo "core.hooksPath = $CURRENT"
    else
        echo "core.hooksPath = （未设置，钩子未启用）"
    fi
    echo ""
    echo "可用钩子："
    for h in "$HOOKS_DIR"/*; do
        [ -f "$h" ] || continue
        if [ -x "$h" ]; then
            echo "  ✓ $(basename "$h")（可执行）"
        else
            echo "  · $(basename "$h")（无执行位，Git for Windows 下仍可运行）"
        fi
    done
}

case "$MODE" in
    enable)
        if [ ! -d "$HOOKS_DIR" ]; then
            echo "✗ 未找到 $HOOKS_DIR 目录，请确认在仓库根目录执行。" >&2
            exit 1
        fi
        git config core.hooksPath "$HOOKS_DIR"
        chmod +x "$HOOKS_DIR"/* 2>/dev/null || true
        echo "✓ Git 钩子已启用：core.hooksPath = $HOOKS_DIR"
        echo ""
        print_status
        echo ""
        echo "提示：commit-msg 会校验 Conventional Commits；pre-commit 做秒级轻量检查。"
        echo "      需要本地全量静态检查（较慢）：YL_PRECOMMIT_FULL=1 git commit -m \"...\""
        ;;
    --status | status)
        print_status
        ;;
    --uninstall | uninstall)
        git config --unset core.hooksPath 2>/dev/null || true
        echo "✓ 已关闭 Git 钩子（core.hooksPath 已清除）。"
        echo "  注意：CI 不受影响，提交规范仍会在流水线中校验。"
        ;;
    *)
        echo "用法: bash scripts/setup-hooks.sh [--status|--uninstall]" >&2
        exit 2
        ;;
esac
