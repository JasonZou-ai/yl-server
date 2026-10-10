-- =============================================================================
-- D-3 报表与监管域后端 · DDL 变更补丁提案（变更留痕）
--
-- 提案人：架构师（高见远）  日期：2026-09-28
-- 依据：docs/reviews/D3-foundation-check.md §6.1 裁决 1-A（用户 2026-09-28 拍板）
--       openapi/yl-api.yaml v1.1.1（superviseExportCreate：x-data-scope.extra）
--       docs/ADR/0003-id-timezone-fk-softdelete.md（ER-09/12/13）
--       yl-modules/yl-module-account/.../security/DataScopePermissionHandler.java（第三闸四表）
--
-- 变更性质：**非破坏、可回退**（+1 可空列 + 1 普通索引）。
--   回退：ALTER TABLE `export_task` DROP INDEX `idx_org_time`, DROP COLUMN `org_id`;
--
-- 缺口（D3-foundation-check §1 发现 2）：
--   `export_task` 无机构锚列（仅 operator_id），且未登记进 ORG_COLUMN
--   → scope=2（机构管理员）查询导出任务时**不加任何机构条件 = 静默放行**（跨机构可读）。
--   与 D-2 `elder.user_id` 同源教训（"该登记没登记"），方向相反（此处为"无归属列 ⇒ 过度可达"）。
--
-- 落地方案 A（裁决 1）：
--   ① 新增可空 `org_id`（NULL 行在 scope=2 下因 `IN` 不匹配而自然不可见 = "NULL 失败即拒"）；
--   ② 回填既有行（由 operator_id 经机构成员关系推导）；
--   ③ 登记进 ORG_COLUMN（export_task → org_id），与既有 SELF_COLUMN（operator_id）并列共存
--      —— 与 `eval_task`（org_id + assessor_id 双登记）同范式。
--
-- 保留字核查：`org_id` **非** MySQL 8 保留字（库内 org_bed/staff/sys_org_member/eval_task/
--   eval_order/eval_report/care_task/supervise_report 等已有多列同名列），
--   故**不触发** verify-schema.sh L266 的保留字金丝雀断言（命中数恒 = 1）。
--   实体侧无需反引号映射。
-- =============================================================================

USE `yl_evaluation`;

-- ---------- 1) 新增机构锚列（内联进 02_schema.sql 的 export_task 建表语句）----------
ALTER TABLE `export_task`
  ADD COLUMN `org_id` BIGINT NULL
    COMMENT '归属机构（D-3 裁决 1-A：导出范围不得超过登录态数据域；可空）' AFTER `operator_id`,
  ADD KEY `idx_org_time` (`org_id`, `created_at`);
-- 引用完整性：ADR-0003 全库 0 物理外键，仅逻辑引用（不建 FK）。

-- ---------- 2) 回填既有行（由 operator_id 推导机构）----------
-- 口径：经 sys_org_member（生效成员 status=1 且未删除）把 operator_id 映射到 org_id。
-- 说明：本表为导出任务，历史行量级小；如某用户跨多机构，取其一（回填仅用于让历史行在
--       scope=2 下可被本机构管理员看见，不追求精确归属）。已绑定 org_id 的行不覆盖。
UPDATE `export_task` et
  JOIN `sys_org_member` m
    ON m.`user_id` = et.`operator_id`
   AND m.`deleted` = 0
   AND m.`status` = 1
   SET et.`org_id` = m.`org_id`
  WHERE et.`org_id` IS NULL;

-- ---------- 3) 校验（可选）----------
-- SELECT COUNT(*) AS total, COUNT(org_id) AS with_org FROM `yl_evaluation`.`export_task`;
