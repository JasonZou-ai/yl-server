-- =============================================================================
-- D-5 保留期链路收官 · DDL 变更补丁提案（变更留痕）
--
-- 提案人：工程师（Alex）  日期：2026-10-09
-- 依据：docs/design/D5-retention-chain-finale-design.md §3.1（裁决 D5-D1，用户 2026-10-09 拍板）
--       docs/reviews/D5-foundation-check.md §2.2 / §3.1（supervise_report.status 不承载归档 → 必须加列）
--
-- 变更性质：**非破坏、可回退**（+1 可空列，无数据搬迁，不新增索引）。
--   回退：ALTER TABLE `supervise_report` DROP COLUMN `archived_at`;
--
-- 缺口（D5-foundation-check §2）：
--   D3-R4——`supervise_report.retain_until` 有写入口（B10：now + 5 年），但 RetentionChainScheduler
--   **不含**该表 ⇒ `retain_until` 只写不读，任务"上线了但一行都不会归档"（静默失效）。
--   而本表 `status` 为 TINYINT 0-3（**上报状态**：0-待上报/1-成功/2-失败/3-重试中），**不承载归档**
--   ⇒ 无法直接复刻 eval_order 的 `SET status='ARCHIVED'`，**必须新增归档态承载列**。
--
-- 落地方案（裁决 D5-D1 = A）：
--   新增可空 `archived_at DATETIME NULL`；到期由 SuperviseReportArchiveTask **只读封存**
--   （`SET archived_at = NOW()`，**行保留**，`status` 上报状态不变）。
--   与 ER-03「5 年留存对象只脱业务视图、不销毁」**同构**：监管上报是对外凭证，销毁不可逆。
--
-- 索引：**不新增**。`idx_retain (retain_until)` ER-03 落地时已建，扫描支撑就绪。
--
-- 保留字核查：`archived_at` **非** MySQL 8 保留字，故**不触发** verify-schema.sh 的保留字金丝雀
--   断言（命中数恒 = 1）；实体侧无需反引号映射。
--
-- 同步动作：
--   ① docker/mysql/init/02_schema.sql 的 supervise_report 建表语句内**内联**该列（供全新库）；
--   ② scripts/verify-schema.sh 断言 83 → 84（新增「archived_at 存在且 datetime」1 条）；
--   ③ yl-module-supervise 实体 SuperviseReport 补列 archivedAt。
-- =============================================================================

USE `yl_evaluation`;

-- ---------- 1) 新增归档态承载列（内联进 02_schema.sql 的 supervise_report 建表语句）----------
ALTER TABLE `supervise_report`
  ADD COLUMN `archived_at` DATETIME NULL
    COMMENT '归档时间（ER-03 到期只读封存，非物理删除）' AFTER `retain_until`;
-- 引用完整性：ADR-0003 全库 0 物理外键，仅逻辑引用（不建 FK）。
-- 索引：不新增；`idx_retain(retain_until)` 已存在。

-- ---------- 2) 校验（可选）----------
-- SELECT COUNT(*) AS total, COUNT(archived_at) AS archived FROM `yl_evaluation`.`supervise_report`;
