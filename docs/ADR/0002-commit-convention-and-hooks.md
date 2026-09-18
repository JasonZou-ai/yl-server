# ADR-0002 提交规范与本地 Git 钩子

- 状态：已采纳
- 日期：2026-09-18
- 关联任务：B1-1 技术选型与工程脚手架（含子任务 r0wpMZ 代码规范与静态检查）

## 背景

ADR-0001 把格式与静态检查绑定到了 `mvn verify`，本地与 CI 已有统一门禁。但 `r0wpMZ` 的验收范围还包含
两项当时未落地的内容：

1. **提交信息规范**：方案里写了 Conventional Commits，但没有任何工具约束，纯靠自觉。
2. **本地提交前钩子**：方案里写了 pre-commit 可选，实际未接入。

提交历史一旦杂乱，`git log` 就无法用于生成变更日志（changelog）、无法按类型筛选回溯（如定位所有
`fix(rule)` 以复核国标分级引擎的修复记录），对需要长期留痕、且要向上级监管说明变更来源的项目是实际损失。

## 决策

1. **不引入 Node 生态**。项目是纯 Java/Maven 仓库，为一条提交信息校验引入 `package.json` +
   `node_modules` + `@commitlint/cli` 会带来额外的供应链面与安装步骤（Node 版本、CI 缓存、依赖审计），
   收益不成比例。改用 **POSIX sh 脚本 + Git 原生 `core.hooksPath`** 实现，零第三方依赖。
2. **同一份脚本，本地与 CI 复用**。`.githooks/commit-msg` 既作为本地钩子，也被 CI 的 `commit-lint`
   作业调用，**口径唯一**，避免"本地过、CI 挂"。
3. **`core.hooksPath` 而非拷贝到 `.git/hooks`**。钩子随仓库版本控制，改一次全员生效；
   `.git/hooks` 是本地目录、不进版本库，无法协作。启用方式为 `bash scripts/setup-hooks.sh`。
4. **pre-commit 只做秒级检查，不跑 Maven**。这是关键取舍：

   | 方案 | 后果 |
   |---|---|
   | pre-commit 跑 `mvn verify`（~30s） | 开发者会习惯性 `--no-verify`，门禁**事实上失效** |
   | pre-commit 只做秒级检查 + CI 兜底全量 | 高频小问题本地即拦，慢检查交给 CI |

   故默认只查：构建产物/二进制误提交、敏感凭据文件与私钥内容、超大文件、CRLF 行尾、调试残留
   （`System.out` / `printStackTrace`，与 Checkstyle 同规则的**提前反馈**，不替代 Checkstyle）。
   需要本地全量预检时显式开启：`YL_PRECOMMIT_FULL=1 git commit -m "..."`。
5. **校验只约束首行**。`<type>(<scope>)!: <描述>` 严格校验；正文与 footer 不设限，鼓励写清
   "为什么这么改"——尤其是国标规则类改动，需要留下依据条款。
6. **CI 侧按事件区分校验范围**：PR 校验 `base..head` 的全部提交；push 校验本次推送的提交区间
   （`before..after`，新分支的 `before` 为全零时退化为只校验 HEAD）。

## 允许的 type

| type | 用途 |
|---|---|
| `feat` | 新功能 |
| `fix` | 缺陷修复 |
| `docs` | 文档（含 PRD、ADR、接口说明） |
| `style` | 不影响语义的格式调整 |
| `refactor` | 重构（非新增功能、非修缺陷） |
| `perf` | 性能优化 |
| `test` | 测试相关 |
| `build` | 构建系统 / 依赖 |
| `ci` | CI 配置与脚本 |
| `chore` | 杂项（不含上述任何一类） |
| `revert` | 回滚提交 |

`Merge*` / `Revert*` / `fixup!` / `squash!` 开头的 Git 自动生成信息直接放行。

## 后果

- 正面：提交历史可机读，`git log --grep` 可按模块与类型筛选；国标规则类改动有据可查；
  敏感文件与私钥在本地即被拦截，降低凭据入库风险。
- 代价：开发者首次 clone 后需执行一次 `bash scripts/setup-hooks.sh`（未启用时 CI 仍会兜底，
  不会漏网）。已写入 `README.md` §2 与本节。
- 应急通道：`git commit --no-verify` 可绕过本地钩子（**CI 不可绕过**）；
  `bash scripts/setup-hooks.sh --uninstall` 可关闭本仓库钩子。

## 相关文件

| 文件 | 说明 |
|---|---|
| `.githooks/commit-msg` | Conventional Commits 校验（本地钩子 + CI 复用） |
| `.githooks/pre-commit` | 提交前轻量防线（秒级） |
| `scripts/setup-hooks.sh` | 一键启用 / 查看状态 / 关闭 |
| `.github/workflows/ci.yml` | `commit-lint` 作业调用同一份 `commit-msg` 脚本 |
