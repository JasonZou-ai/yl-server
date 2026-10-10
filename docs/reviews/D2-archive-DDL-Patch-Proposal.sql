-- =============================================================================
-- D-2 档案域 DDL 变更提案（裁决二·方案 A）：elder 自见锚列 user_id
-- =============================================================================
-- 角色：架构师（高见远）｜落地：工程师｜依据：docs/design/D2-archive-domain-design.md §1 裁决二、§6
--
-- 变更内容：为 `elder` 增列 `user_id`（BIGINT NULL）+ 唯一键 `uk_elder_user`。
--
-- 【为什么需要这一列】
--   契约 `by-role: {ELDER: self}` 声明老人端"仅见本人"，且 `elder:archive:read` 已授予 ELDER
--   （docker/mysql/init/03_seed_rbac.sql 中 sys_role_permission id 3027 为权威事实）。
--   但 `elder` 表此前无任何指向登录账号的列 → scope=1 下只能"失败即拒"（恒假 1=0），
--   结果是"权限已授、语义不可达"的静默失效。故新增锚列，使 scope=1 语义结构上可实现。
--   语义：elder.user_id = sys_user.id（老人本人的登录账号）；可空，未绑定为 NULL。
--
-- 【为什么 user_id 不撞保留字金丝雀】
--   库内已存在多列 `user_id`（login_log / sys_user_role / idempotent_record / track_event /
--   sys_user_third_party），而 verify-schema.sh 的保留字金丝雀恒为 1（唯一命中
--   audit_log.sensitive）。若 USER_ID 命中 KEYWORDS(RESERVED=1)，命中数早已 > 1，与实测 = 1
--   矛盾。故 `user_id` 非 MySQL 保留字，新增不改变金丝雀命中数。
--
-- 【落地口径】
--   按本项目惯例，列与唯一键**内联进 docker/mysql/init/02_schema.sql 的 `elder` 建表语句**
--   （基线自带）；本文件作为变更留痕保留（等价 ALTER 语句 + 依据注释）。
--
-- 【引用完整性】
--   ADR-0003：全库 0 物理外键，仅逻辑引用（不建 FK）。
-- =============================================================================

USE `yl_evaluation`;

-- D-2 裁决二·方案 A：ELDER(scope=1) 自见锚列。
ALTER TABLE `elder`
  ADD COLUMN `user_id` BIGINT NULL COMMENT '本人登录账号（sys_user.id，应用层引用；可空）' AFTER `org_id`,
  ADD UNIQUE KEY `uk_elder_user` (`user_id`);

-- 回滚（仅用于本地演练，勿在生产执行）：
-- ALTER TABLE `elder` DROP INDEX `uk_elder_user`, DROP COLUMN `user_id`;
