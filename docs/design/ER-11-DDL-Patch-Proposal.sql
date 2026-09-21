-- =============================================================================
-- ER-11 DDL 补丁提案 · 埋点事件名字典表（track_event_dict）
--   ✅ 状态：**已应用（2026-09-21）**。ER 通过后正文已并入 docker/mysql/init/02_schema.sql
--            （域 8 治理域），并同步 verify-schema.sh（表数 37→39、active_uk 3→5、
--            deleted 19→21、断言总数 60）。
--   ⚠ 本文件保留为变更留痕，**请勿重复执行**（CREATE TABLE IF NOT EXISTS + ON DUPLICATE KEY UPDATE，重复执行无害）。
--
-- 依据：docs/design/ER-11-track-event-dictionary.md §7（合并定稿 v1.1，30 事件）
-- 口径：ADR-0003（应用层主键 / DATETIME / 无外键 / deleted + active_uk）
--
-- 【为什么必须补断言】补丁落地后计数发生变化（实测见 docs/quality/verify-schema-assertion-reconciliation.md）：
--   表总数        37 → 39   （+track_event_dict、+sys_user_third_party）
--   active_uk 列   3 → 5
--   deleted 列    19 → 21
--   retain_until   4 → 5   （ER-14 PM 裁决新增列）
-- =============================================================================

USE `yl_evaluation`;

-- -----------------------------------------------------------------------------
-- 埋点事件元数据字典：上报白名单 + 敏感分级 + 留存治理
--   · 与代码枚举 EventCode 一一对应；枚举为真源，本表为运行时投影（ER-11 §10 决议 D4）
--   · 上报 event_code 不在本表（且 status=1）→ 网关拒绝写入并告警（ER-11 §7 校验规则）
--   · 应用层主键、UNIQUE 带 active_uk、DATETIME、无外键 —— 严格遵循 ADR-0003
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `track_event_dict` (
    `id`           BIGINT       NOT NULL COMMENT '主键（应用层生成，ADR-0003）',
    `event_code`   VARCHAR(64)  NOT NULL COMMENT '事件编码（与代码枚举 EventCode 一一对应）',
    `event_name`   VARCHAR(128) NOT NULL COMMENT '中文名',
    `module`       VARCHAR(64)  NULL     COMMENT '归属模块',
    `page`         VARCHAR(128) NULL     COMMENT '归属页面（白名单）',
    `param_schema` JSON         NULL     COMMENT '参数结构（schema）',
    `sensitivity`  ENUM('s0','s1','s2') NOT NULL DEFAULT 's0'
                   COMMENT '敏感级别（s0 不含个人信息 / s1 可识别需脱敏 / s2 敏感个人信息禁采原文）',
    `retain_days`  INT          NOT NULL DEFAULT 180 COMMENT '留存天数（对齐 PRD §9 / M1 §5）',
    `status`       TINYINT      NOT NULL DEFAULT 1    COMMENT '0-停用 1-启用',
    `deleted`      TINYINT      NOT NULL DEFAULT 0,
    `active_uk`    TINYINT GENERATED ALWAYS AS (IF(`deleted` = 0, 1, NULL)) STORED,
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_event_code` (`event_code`, `active_uk`),
    KEY `idx_module_page` (`module`, `page`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='埋点事件元数据字典（ER-11）';

-- -----------------------------------------------------------------------------
-- 字典种子：30 条（ER-11 §2 定稿；敏感分布 s0=4 / s1=14 / s2=12）
--   留存一律 180 天（对齐 ER-03 / PRD §9）；status 全为 1（启用）
--   ⚠ 未采集 device_id（DPO-1 决议）、user_id 一律 HMAC 摘要
-- -----------------------------------------------------------------------------
INSERT INTO `track_event_dict`
    (`id`, `event_code`, `event_name`, `module`, `sensitivity`, `retain_days`, `status`)
VALUES
    (1101, 'auth_login_start',          '发起登录',       'auth',       's0', 180, 1),
    (1102, 'auth_login_result',         '登录结果',       'auth',       's1', 180, 1),
    (1103, 'auth_logout',               '退出登录',       'auth',       's0', 180, 1),
    (1104, 'elder_create_submit',       '提交建档',       'elder',      's1', 180, 1),
    (1105, 'elder_create_result',       '建档结果',       'elder',      's1', 180, 1),
    (1106, 'elder_info_edit',           '编辑档案',       'elder',      's1', 180, 1),
    (1107, 'elder_search',              '检索老人',       'elder',      's2', 180, 1),
    (1108, 'assessment_create',         '新建评估单',     'assessment', 's2', 180, 1),
    (1109, 'assessment_item_answer',    '单项作答',       'assessment', 's2', 180, 1),
    (1110, 'assessment_submit',         '提交评估',       'assessment', 's2', 180, 1),
    (1111, 'assessment_draft_save',     '暂存草稿',       'assessment', 's0', 180, 1),
    (1112, 'grade_auto_result',         '分级完成',       'grade',      's1', 180, 1),
    (1113, 'grade_upgrade_reason',      '上调原因',       'grade',      's2', 180, 1),
    (1114, 'report_generate',           '生成报告',       'report',     's1', 180, 1),
    (1115, 'report_view',               '查看报告',       'report',     's2', 180, 1),
    (1116, 'report_share',              '分享报告',       'report',     's1', 180, 1),
    (1117, 'report_export',             '导出报告',       'report',     's2', 180, 1),
    (1118, 'care_plan_generate',        '生成照护计划',   'care',       's1', 180, 1),
    (1119, 'care_plan_edit',            '编辑照护计划',   'care',       's1', 180, 1),
    (1120, 'care_plan_confirm',         '确认照护计划',   'care',       's1', 180, 1),
    (1121, 'care_task_complete',        '任务完成',       'care',       's0', 180, 1),
    (1122, 'archive_elder_view',        '查看档案',       'archive',    's1', 180, 1),
    (1123, 'archive_plaintext_view',    '查看明文敏感字段','archive',   's2', 180, 1),
    (1124, 'archive_download',          '档案下载',       'archive',    's2', 180, 1),
    (1125, 'archive_access_log_query',  '调阅访问记录',   'archive',    's1', 180, 1),
    (1126, 'bind_relative',             '亲情绑定',       'bind',       's2', 180, 1),
    (1127, 'consent_record',            '同意留痕',       'consent',    's1', 180, 1),
    (1128, 'offline_sync',              '离线同步',       'sync',       's2', 180, 1),
    (1129, 'page_stay',                 '页面停留',       'page',       's2', 180, 1),
    (1130, 'error_occur',               '异常发生',       'error',      's1', 180, 1);

-- -----------------------------------------------------------------------------
-- 断言增补（并入 verify-schema.sh，共 3 项；另有 1 项为「改值不改数」）
-- -----------------------------------------------------------------------------
-- [ER-11 断言 1] 表存在 = 1
-- SELECT COUNT(*) FROM information_schema.tables
--  WHERE table_schema='yl_evaluation' AND table_name='track_event_dict';
--
-- [ER-11 断言 2] 字典种子事件数 = 30（且敏感分布 s0=4 / s1=14 / s2=12）
-- SELECT COUNT(*) FROM track_event_dict WHERE deleted=0;
-- SELECT sensitivity, COUNT(*) FROM track_event_dict WHERE deleted=0 GROUP BY sensitivity;
--
-- [ER-11 断言 3] 禁采字段不得落库：全库不得存在 device_id 列（DPO-1 决议）
-- SELECT COUNT(*) FROM information_schema.columns
--  WHERE table_schema='yl_evaluation' AND column_name='device_id';   -- 期望 0
--
-- [ER-11 改值不改数] 由既有全库断言自动覆盖，仅需更新期望值：
--   · 表总数 37 → 39
--   · 生成列 active_uk 数量 3 → 5
--   · 软删除列 deleted 19 → 21
--   · ER-09 自增列 = 1 —— 本表主键为应用层生成，**不新增自增列**，断言值保持 1
