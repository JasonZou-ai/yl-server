-- C3 报告侧规则绑定 · DDL 补丁提案
-- 依据：PRD §1.4（报告须展示初判等级 + 终判等级 + 等级变化原因，并绑定规则版本支持事后复算）
-- 关联：ADR-0005（MVP 不引入独立 API 网关）、C1 评估中心接入引擎、C2 分级引擎（ruleVersion 1.0.0）
-- 适用：02_schema.sql 已执行的存量库（全新库直接由 02_schema.sql 建表，无需本补丁）

USE `yl_evaluation`;

-- ---------------------------------------------------------------------------
-- 1) 规则版本口径对齐：占位值 'v1' → 权威规则集口径 '1.0.0'
--    原因：引擎判定源 rules/gbt42195-ruleset.json 的 ruleVersion = 1.0.0，
--          评估单按 version 绑定规则版本，两者必须一致，否则事后复算取不到版本。
--    约束：gb_rule_version 必须保持单行（verify-schema 断言 COUNT=1），故用 UPDATE 而非 INSERT。
-- ---------------------------------------------------------------------------
UPDATE `gb_rule_version`
   SET `version` = '1.0.0',
       `remark`  = '国标基线版本：4 一级指标/26 二级指标/总分 90/5 等级（与引擎 RuleSet 同源）'
 WHERE `id` = 1 AND `version` <> '1.0.0';

-- ---------------------------------------------------------------------------
-- 2) eval_order：初判 / 终判 / 变化原因 / 规则指纹
-- ---------------------------------------------------------------------------
ALTER TABLE `eval_order`
    ADD COLUMN IF NOT EXISTS `preliminary_grade_code` VARCHAR(16)  NULL COMMENT '初判等级码（引擎 preliminaryLevelCode，L0~L4）' AFTER `upgrade_reason`,
    ADD COLUMN IF NOT EXISTS `preliminary_level_name` VARCHAR(64)  NULL COMMENT '初判等级名称（如 能力完好）' AFTER `preliminary_grade_code`,
    ADD COLUMN IF NOT EXISTS `final_level_code`       VARCHAR(16)  NULL COMMENT '终判等级码（引擎 levelCode，L0~L4）' AFTER `preliminary_level_name`,
    ADD COLUMN IF NOT EXISTS `final_level_name`       VARCHAR(64)  NULL COMMENT '终判等级名称' AFTER `final_level_code`,
    ADD COLUMN IF NOT EXISTS `grade_change_reason`    VARCHAR(255) NULL COMMENT '等级变化原因（初判→终判，特殊规则触发依据）' AFTER `final_level_name`,
    ADD COLUMN IF NOT EXISTS `rule_sha`               CHAR(64)     NULL COMMENT '判定所用规则集内容指纹 SHA-256（事后复算锚点）' AFTER `grade_change_reason`;

-- ---------------------------------------------------------------------------
-- 3) eval_report：绑定规则版本与指纹 + 初判/终判/变化原因
-- ---------------------------------------------------------------------------
ALTER TABLE `eval_report`
    ADD COLUMN IF NOT EXISTS `rule_version_id`        BIGINT      NULL COMMENT '判定所用国标规则版本（gb_rule_version.id）' AFTER `total_score`,
    ADD COLUMN IF NOT EXISTS `rule_sha`               CHAR(64)    NULL COMMENT '判定所用规则集内容指纹 SHA-256' AFTER `rule_version_id`,
    ADD COLUMN IF NOT EXISTS `preliminary_grade_code` VARCHAR(16) NULL COMMENT '初判等级码（L0~L4）' AFTER `rule_sha`,
    ADD COLUMN IF NOT EXISTS `preliminary_level_name` VARCHAR(64) NULL COMMENT '初判等级名称' AFTER `preliminary_grade_code`,
    ADD COLUMN IF NOT EXISTS `final_level_code`       VARCHAR(16) NULL COMMENT '终判等级码（L0~L4）' AFTER `preliminary_level_name`,
    ADD COLUMN IF NOT EXISTS `final_level_name`       VARCHAR(64) NULL COMMENT '终判等级名称' AFTER `final_level_code`,
    ADD COLUMN IF NOT EXISTS `level_change_reason`    VARCHAR(255) NULL COMMENT '等级变化原因（初判→终判）' AFTER `final_level_name`;

-- ---------------------------------------------------------------------------
-- 4) eval_item：26 项二级指标题库播种（全新库已含；存量库执行本段补齐）
--    id 采用引擎指标 id（101~405），使 eval_answer.item_id 与引擎 indicatorId 天然对齐。
--    score_max 合计 = 32+15+28+15 = 90（＝规则集 fullScore）。
-- ---------------------------------------------------------------------------
INSERT INTO `eval_item`
    (`id`, `rule_version_id`, `dimension_code`, `item_code`, `item_name`, `score_min`, `score_max`, `weight`, `need_evidence`, `is_required`, `order_no`, `deleted`) VALUES
 (101, 1, 1, '101', '进食',               0, 4, 1.00, 0, 1,  1, 0),
 (102, 1, 1, '102', '修饰',               0, 4, 1.00, 0, 1,  2, 0),
 (103, 1, 1, '103', '洗澡',               0, 4, 1.00, 0, 1,  3, 0),
 (104, 1, 1, '104', '穿/脱上衣',          0, 4, 1.00, 0, 1,  4, 0),
 (105, 1, 1, '105', '穿/脱裤子和鞋袜',     0, 4, 1.00, 0, 1,  5, 0),
 (106, 1, 1, '106', '小便控制',           0, 4, 1.00, 0, 1,  6, 0),
 (107, 1, 1, '107', '大便控制',           0, 4, 1.00, 0, 1,  7, 0),
 (108, 1, 1, '108', '如厕',               0, 4, 1.00, 0, 1,  8, 0),
 (201, 1, 2, '201', '床上体位转移',       0, 4, 1.00, 0, 1,  9, 0),
 (202, 1, 2, '202', '床椅转移',           0, 4, 1.00, 0, 1, 10, 0),
 (203, 1, 2, '203', '平地行走',           0, 4, 1.00, 0, 1, 11, 0),
 (204, 1, 2, '204', '上下楼梯',           0, 3, 1.00, 0, 1, 12, 0),
 (301, 1, 3, '301', '时间定向',           0, 4, 1.00, 0, 1, 13, 0),
 (302, 1, 3, '302', '空间定向',           0, 4, 1.00, 0, 1, 14, 0),
 (303, 1, 3, '303', '人物定向',           0, 4, 1.00, 0, 1, 15, 0),
 (304, 1, 3, '304', '记忆',               0, 4, 1.00, 0, 1, 16, 0),
 (305, 1, 3, '305', '理解能力',           0, 4, 1.00, 0, 1, 17, 0),
 (306, 1, 3, '306', '表达能力',           0, 4, 1.00, 0, 1, 18, 0),
 (307, 1, 3, '307', '攻击行为',           0, 1, 1.00, 0, 1, 19, 0),
 (308, 1, 3, '308', '抑郁症状',           0, 1, 1.00, 0, 1, 20, 0),
 (309, 1, 3, '309', '意识水平',           0, 2, 1.00, 0, 1, 21, 0),
 (401, 1, 4, '401', '视力',               0, 2, 1.00, 0, 1, 22, 0),
 (402, 1, 4, '402', '听力',               0, 2, 1.00, 0, 1, 23, 0),
 (403, 1, 4, '403', '执行日常事务',       0, 4, 1.00, 0, 1, 24, 0),
 (404, 1, 4, '404', '使用交通工具外出',   0, 3, 1.00, 0, 1, 25, 0),
 (405, 1, 4, '405', '社会交往能力',       0, 4, 1.00, 0, 1, 26, 0)
ON DUPLICATE KEY UPDATE `item_name` = VALUES(`item_name`), `score_max` = VALUES(`score_max`);

-- ---------------------------------------------------------------------------
-- 5) 验证查询（执行后人工核对）
-- ---------------------------------------------------------------------------
-- 期望 version = '1.0.0' 且仅 1 行
--   SELECT COUNT(*) AS cnt, GROUP_CONCAT(version) FROM gb_rule_version;
--
-- 期望 6（eval_order 新增列全部存在）
--   SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='yl_evaluation'
--     AND table_name='eval_order'
--     AND column_name IN ('preliminary_grade_code','preliminary_level_name','final_level_code',
--                         'final_level_name','grade_change_reason','rule_sha');
--
-- 期望 7（eval_report 新增列全部存在）
--   SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='yl_evaluation'
--     AND table_name='eval_report'
--     AND column_name IN ('rule_version_id','rule_sha','preliminary_grade_code','preliminary_level_name',
--                         'final_level_code','final_level_name','level_change_reason');
--
-- 期望 26（题库播种完整）
--   SELECT COUNT(*) FROM eval_item WHERE deleted=0;
--
-- 期望 90（题库满分合计 ＝ 规则集 fullScore）
--   SELECT SUM(score_max) FROM eval_item WHERE deleted=0;
--
-- 门禁：bash scripts/verify-schema.sh（断言总数 69，其中 C3 报告侧规则绑定 6 项）
