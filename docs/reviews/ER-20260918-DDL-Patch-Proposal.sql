-- =============================================================================
-- ER 三方评审 DDL 补丁提案（PROPOSAL — 未应用）
-- 来源：ER-Review-20260918.md 裁决（ER-03 / ER-04 / ER-05 / ER-06 / ER-07 / ER-08）
-- 目标库：银龄守护 yl-server
-- 应用时机：B2 建库前；应用后必须复跑 scripts/verify-schema.sh
--           （订正：本补丁新增 **3 表** → 表总数 34 → **37**；原稿误写「新增 1 表 / 34→35」）
-- 状态：✅ **已应用 2026-09-19** —— 因 B2 建库尚未发生，按「基线自带」方式**内联进 02_schema.sql**，
--       未以 ALTER 形式追加；复验 `scripts/verify-schema.sh` **29 项全绿**（表总数 37 / 索引 126）。
--       本文件保留为**变更留痕**（不再作为待执行脚本）。
-- =============================================================================

USE `yl_evaluation`;  -- 实际库名（与 scripts/verify-schema.sh 一致）

-- -----------------------------------------------------------------------------
-- ER-03 保留期（保留期口径：评估记录/报告 5 年 · 审计日志 3 年 · 监管报表 5 年 · 埋点 180 天）
-- 说明：仅新增 retain_until 列 + 索引，作为「过期清理 / 归档」任务的触发依据；
--       具体写入值由应用层按裁决口径计算（如 eval_order 归档时 NOW()+5Y）。
-- -----------------------------------------------------------------------------

-- 评估记录（5 年）
ALTER TABLE `eval_order`
  ADD COLUMN `retain_until` DATETIME NULL COMMENT '保留至（评估记录 5 年）',
  ADD KEY `idx_retain` (`retain_until`);

-- 评估报告（5 年）
ALTER TABLE `eval_report`
  ADD COLUMN `retain_until` DATETIME NULL COMMENT '保留至（报告 5 年）',
  ADD KEY `idx_retain` (`retain_until`);

-- 审计日志（3 年）
ALTER TABLE `audit_log`
  ADD COLUMN `retain_until` DATETIME NULL COMMENT '保留至（审计日志 3 年）',
  ADD KEY `idx_retain` (`retain_until`);

-- 监管报表（5 年）
ALTER TABLE `supervise_report`
  ADD COLUMN `retain_until` DATETIME NULL COMMENT '保留至（监管报表 5 年）',
  ADD KEY `idx_retain` (`retain_until`);

-- track_event 已有 expire_at（≤180 天），不变。

-- -----------------------------------------------------------------------------
-- ER-05 乐观锁 / 版本号列（离线冲突检测依据）
-- 冲突口径：服务端为准。客户端提交携带 row_version，不一致则拒绝并返回冲突提示。
-- -----------------------------------------------------------------------------

ALTER TABLE `eval_order`
  ADD COLUMN `row_version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号（离线冲突检测）';

ALTER TABLE `eval_answer`
  ADD COLUMN `row_version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号（离线冲突检测）';

-- 可选扩展（按 B3 离线能力确认后再加，勿提前扩面）：
-- ALTER TABLE `eval_task`  ADD COLUMN `row_version` INT NOT NULL DEFAULT 0;
-- ALTER TABLE `elder`      ADD COLUMN `row_version` INT NOT NULL DEFAULT 0;
-- ALTER TABLE `care_plan`  ADD COLUMN `row_version` INT NOT NULL DEFAULT 0;

-- -----------------------------------------------------------------------------
-- ER-06 作答三语义（区分：应填未填 / 分支未触达 / 拒答）
-- 拒答计分沿用 PRD v1.1：记 0 分、分母不缩水 + 人工复核。
-- -----------------------------------------------------------------------------

ALTER TABLE `eval_answer`
  ADD COLUMN `answer_state` TINYINT NOT NULL DEFAULT 0
    COMMENT '作答状态：0-未作答 1-已作答 2-不适用(分支未触达) 3-拒答',
  ADD KEY `idx_order_state` (`order_id`, `answer_state`);
-- 兼容：保留 is_refused（1-拒答），与 answer_state=3 并存，由应用层保持一致。

ALTER TABLE `eval_item`
  ADD COLUMN `is_required` TINYINT NOT NULL DEFAULT 0
    COMMENT '1-必答（应填未填即阻断提交）';

-- -----------------------------------------------------------------------------
-- ER-07 授权「长期」语义（避免到期失效任务误杀长期授权）
-- 语义：长期 = is_permanent=1 且 valid_to IS NULL
-- 失效任务排除条件：status=1 AND (is_permanent=1 OR valid_to > NOW())
-- -----------------------------------------------------------------------------

ALTER TABLE `elder_authorization`
  ADD COLUMN `is_permanent` TINYINT NOT NULL DEFAULT 0
    COMMENT '1-长期有效（valid_to 为 NULL）',
  ADD KEY `idx_expire_scan` (`status`, `is_permanent`, `valid_to`);

-- -----------------------------------------------------------------------------
-- ER-04 本期纳入：功能点 12 照护任务派发（任务 / 执行打卡 / 完成率）
-- 功能点 20 自定义报表 → 移 V2，本期以固定报表 + export_task 承载，不建表。
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `care_task` (
    `id`            BIGINT       NOT NULL,
    `task_no`       VARCHAR(32)  NOT NULL COMMENT '照护任务号',
    `plan_id`       BIGINT       NULL COMMENT '来源照护方案',
    `elder_id`      BIGINT       NOT NULL,
    `org_id`        BIGINT       NOT NULL,
    `assignee_id`   BIGINT       NULL COMMENT '执行人（护理员）',
    `category`      VARCHAR(32)  NULL COMMENT '任务类别（生活照料/康复/用药提醒...）',
    `title`         VARCHAR(128) NOT NULL,
    `content`       VARCHAR(500) NULL,
    `plan_start_at` DATETIME     NULL COMMENT '计划开始',
    `plan_end_at`   DATETIME     NULL COMMENT '计划结束',
    `status`        TINYINT      NOT NULL DEFAULT 0 COMMENT '0-待执行 1-执行中 2-已完成 3-已取消',
    `row_version`   INT          NOT NULL DEFAULT 0,
    `deleted`       TINYINT      NOT NULL DEFAULT 0,
    `created_by`    BIGINT       NULL,
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_no` (`task_no`),
    KEY `idx_assignee_status` (`assignee_id`, `status`),
    KEY `idx_elder` (`elder_id`),
    KEY `idx_org_plan` (`org_id`, `plan_start_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='照护任务派发';

CREATE TABLE IF NOT EXISTS `care_task_log` (
    `id`          BIGINT       NOT NULL,
    `task_id`     BIGINT       NOT NULL,
    `action`      VARCHAR(16)  NOT NULL COMMENT 'START/CHECKIN/DONE/SKIP',
    `checkin_at`  DATETIME     NULL COMMENT '打卡时间',
    `geo_lat`     DECIMAL(10,7) NULL,
    `geo_lng`     DECIMAL(10,7) NULL,
    `photo_key`   VARCHAR(255) NULL COMMENT '打卡照片对象键（可选）',
    `remark`      VARCHAR(500) NULL,
    `operator_id` BIGINT       NULL,
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_task_time` (`task_id`, `checkin_at`),
    KEY `idx_operator` (`operator_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='照护任务执行打卡（完成率数据源）';

-- -----------------------------------------------------------------------------
-- ER-08 告知同意留痕（个保法「知情同意」举证）
-- 与业务授权表（elder_authorization）区分：此处记录对隐私政策的同意，非业务授权。
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `consent_record` (
    `id`              BIGINT       NOT NULL,
    `subject_type`    VARCHAR(16)  NOT NULL COMMENT '主体类型：ELDER/FAMILY/STAFF',
    `subject_id`      BIGINT       NOT NULL COMMENT '主体 ID',
    `policy_code`     VARCHAR(32)  NOT NULL COMMENT '隐私政策编号',
    `policy_version`  VARCHAR(16)  NOT NULL COMMENT '政策版本号',
    `consent_at`      DATETIME     NOT NULL COMMENT '同意时间',
    `consent_channel` VARCHAR(16)  NOT NULL COMMENT '渠道：APP/WECHAT/DOUYIN/IOS/PAPER',
    `scope_json`      JSON         NULL COMMENT '授权收集范围快照',
    `revoked_at`      DATETIME     NULL COMMENT '撤回时间',
    `ip`              VARCHAR(45)  NULL,
    `deleted`         TINYINT      NOT NULL DEFAULT 0,
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_subject` (`subject_type`, `subject_id`),
    KEY `idx_policy` (`policy_code`, `policy_version`),
    KEY `idx_consent_time` (`consent_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告知同意留痕（个保法举证）';

-- =============================================================================
-- 应用清单（B2 建库前执行 + 复验）
--   [x] 研发自测：内联后全新库顺序执行无错（2026-09-19，MySQL 8.0.37 实测）
--   [x] 更新 scripts/verify-schema.sh：表总数断言**动态推导**（实测 37）；新增 6 条补丁断言
--       （retain_until / row_version / answer_state+is_required / is_permanent / consent_record / care_task×2）
--   [x] 5 项行为验证复跑（active_uk 逻辑删除等既有验证不回归）
--   [ ] CI 作业复绿（待推送触发）
--   [ ] DPO 会签（ER-03 / ER-08）
-- =============================================================================
