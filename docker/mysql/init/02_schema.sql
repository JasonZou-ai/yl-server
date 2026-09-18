-- =============================================================================
-- 银龄守护 · 核心域表结构（B1-4 数据库模型设计 / 任务 r4UcEv）
-- 依据：PRD YL-PRD-v1.0 §2 权限矩阵 · §3 功能清单 · §4.4 评估单状态机 · §9 埋点合规
-- 国标：GB/T 42195-2022 老年人能力评估规范（4 一级指标 / 26 二级指标 / 5 等级）
-- 说明：本文件为「结构定义 + 字典播种」；生产变更由 Flyway(src/main/resources/db/migration) 管理。
-- 约定：
--   1) 主键 BIGINT（MyBatis-Plus assign_id 雪花），逻辑删除列 deleted TINYINT(0/1)。
--   2) 敏感字段命名后缀 _enc（密文，AES-256-GCM 信封加密）或 _hash（HMAC-SHA256，供等值检索）。
--      禁止明文落库：身份证号、真实姓名、手机号、住址、健康史、用药记录、人脸特征。
--   3) created_at / updated_at 统一 DATETIME，created_by / updated_by 记录操作人（审计）。
-- =============================================================================

USE `yl_evaluation`;

SET NAMES utf8mb4;

-- =============================================================================
-- 域 1｜机构域（org）：机构 / 床位 / 人员资质
-- =============================================================================

CREATE TABLE IF NOT EXISTS `org` (
    `id`           BIGINT       NOT NULL COMMENT '主键',
    `org_code`     VARCHAR(64)  NOT NULL COMMENT '机构统一社会信用代码/内部编码',
    `org_name`     VARCHAR(128) NOT NULL COMMENT '机构名称',
    `org_type`     TINYINT      NOT NULL DEFAULT 1 COMMENT '1-养老机构 2-社区中心 3-医院 4-评估中心',
    `region_code`  VARCHAR(12)  NOT NULL COMMENT '行政区划代码（民政上报口径）',
    `address`      VARCHAR(255) NULL COMMENT '机构地址',
    `contact_name` VARCHAR(64)  NULL COMMENT '联系人',
    `contact_phone_enc` VARCHAR(512) NULL COMMENT '联系电话（密文）',
    `status`       TINYINT      NOT NULL DEFAULT 1 COMMENT '0-停用 1-正常',
    `deleted`      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `created_by`   BIGINT       NULL,
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_by`   BIGINT       NULL,
    `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_org_code` (`org_code`),
    KEY `idx_region` (`region_code`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='机构';

CREATE TABLE IF NOT EXISTS `org_bed` (
    `id`         BIGINT      NOT NULL COMMENT '主键',
    `org_id`     BIGINT      NOT NULL COMMENT '所属机构',
    `bed_no`     VARCHAR(32) NOT NULL COMMENT '床位号',
    `status`     TINYINT     NOT NULL DEFAULT 0 COMMENT '0-空闲 1-占用 2-维修',
    `elder_id`   BIGINT      NULL COMMENT '当前入住老人（与档案联动）',
    `deleted`    TINYINT     NOT NULL DEFAULT 0,
    `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_org_bed` (`org_id`, `bed_no`),
    KEY `idx_elder` (`elder_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='床位（P2，本期预留）';

CREATE TABLE IF NOT EXISTS `staff` (
    `id`              BIGINT       NOT NULL COMMENT '主键',
    `org_id`          BIGINT       NOT NULL COMMENT '所属机构',
    `user_id`         BIGINT       NOT NULL COMMENT '关联账号（sys_user）',
    `staff_name_enc`  VARCHAR(512) NULL COMMENT '姓名（密文）',
    `staff_type`      TINYINT      NOT NULL COMMENT '1-评估员 2-照护员 3-机构管理员',
    `cert_no`         VARCHAR(64)  NULL COMMENT '资质证书编号',
    `cert_expire_at`  DATE         NULL COMMENT '资质到期日（到期前30天预警）',
    `title`           VARCHAR(64)  NULL COMMENT '职称（护士/康复师/社工/医生）',
    `status`          TINYINT      NOT NULL DEFAULT 1 COMMENT '0-离职 1-在职',
    `deleted`         TINYINT      NOT NULL DEFAULT 0,
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_org_user` (`org_id`, `user_id`),
    KEY `idx_cert_expire` (`cert_expire_at`, `status`),
    KEY `idx_type` (`org_id`, `staff_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='机构人员（评估员/照护员/管理员）';

-- =============================================================================
-- 域 2｜账号域（account）：账号 / 角色 / 权限 / 组织成员 / 登录日志
-- 对齐 PRD §2：5 角色；录入人 ≠ 复核人（互斥在应用层 + 审计层双重校验）
-- =============================================================================

CREATE TABLE IF NOT EXISTS `sys_user` (
    `id`              BIGINT       NOT NULL COMMENT '主键',
    `username`        VARCHAR(64)  NOT NULL COMMENT '登录名（全局唯一）',
    `phone_enc`       VARCHAR(512) NULL COMMENT '手机号（密文）',
    `phone_hash`      CHAR(64)     NULL COMMENT '手机号 HMAC-SHA256（等值检索）',
    `password_hash`   VARCHAR(100) NULL COMMENT 'BCrypt 口令散列',
    `real_name_enc`   VARCHAR(512) NULL COMMENT '真实姓名（密文）',
    `avatar_cos_key`  VARCHAR(255) NULL COMMENT '头像对象键',
    `status`          TINYINT      NOT NULL DEFAULT 1 COMMENT '0-停用 1-正常 2-锁定',
    `pwd_updated_at`  DATETIME     NULL COMMENT '口令最后修改时间（策略校验）',
    `last_login_at`   DATETIME     NULL,
    `deleted`         TINYINT      NOT NULL DEFAULT 0,
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    KEY `idx_phone_hash` (`phone_hash`),
    KEY `idx_status` (`status`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户账号';

CREATE TABLE IF NOT EXISTS `sys_role` (
    `id`         BIGINT      NOT NULL,
    `role_code`  VARCHAR(32) NOT NULL COMMENT 'ELDER/ASSESSOR/FAMILY/ORG_ADMIN/SUPERVISOR',
    `role_name`  VARCHAR(64) NOT NULL COMMENT '角色名',
    `data_scope` TINYINT     NOT NULL DEFAULT 1 COMMENT '1-本人 2-本机构 3-全量 4-只读全局',
    `deleted`    TINYINT     NOT NULL DEFAULT 0,
    `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_code` (`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色（对应 PRD §2.1 五角色）';

CREATE TABLE IF NOT EXISTS `sys_permission` (
    `id`        BIGINT       NOT NULL,
    `perm_code` VARCHAR(64)  NOT NULL COMMENT '权限码，如 evaluation:order:create',
    `perm_name` VARCHAR(64)  NOT NULL,
    `module`    VARCHAR(32)  NOT NULL COMMENT 'account/evaluation/archive/care/supervise/report',
    `need_second_verify` TINYINT NOT NULL DEFAULT 0 COMMENT '1-需二次验证(导出/作废/解绑)',
    `deleted`   TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_perm_code` (`perm_code`),
    KEY `idx_module` (`module`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权限点';

CREATE TABLE IF NOT EXISTS `sys_user_role` (
    `id`      BIGINT NOT NULL,
    `user_id` BIGINT NOT NULL,
    `role_id` BIGINT NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_role` (`user_id`, `role_id`),
    KEY `idx_role` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户-角色';

CREATE TABLE IF NOT EXISTS `sys_role_permission` (
    `id`      BIGINT NOT NULL,
    `role_id` BIGINT NOT NULL,
    `perm_id` BIGINT NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_perm` (`role_id`, `perm_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-权限';

CREATE TABLE IF NOT EXISTS `sys_org_member` (
    `id`         BIGINT   NOT NULL,
    `org_id`     BIGINT   NOT NULL,
    `user_id`    BIGINT   NOT NULL,
    `role_id`    BIGINT   NOT NULL COMMENT '多角色可多行',
    `is_default` TINYINT  NOT NULL DEFAULT 0 COMMENT '登录默认机构',
    `status`     TINYINT  NOT NULL DEFAULT 1,
    `deleted`    TINYINT  NOT NULL DEFAULT 0,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_org_user_role` (`org_id`, `user_id`, `role_id`),
    KEY `idx_user` (`user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='机构成员（用户多机构多角色归属）';

CREATE TABLE IF NOT EXISTS `login_log` (
    `id`          BIGINT       NOT NULL,
    `user_id`     BIGINT       NULL,
    `username`    VARCHAR(64)  NULL,
    `login_type`  VARCHAR(16)  NOT NULL COMMENT 'WECHAT/DOUYIN/APPLE/PWD/FACE',
    `device_type` VARCHAR(16)  NULL COMMENT 'WXMP/DYMP/IOS/ANDROID/WEB',
    `ip`          VARCHAR(45)  NULL,
    `ua`          VARCHAR(255) NULL,
    `success`     TINYINT      NOT NULL DEFAULT 1,
    `fail_reason` VARCHAR(128) NULL,
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_time` (`user_id`, `created_at`),
    KEY `idx_time` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录日志';

-- =============================================================================
-- 域 3｜老人档案域（archive）：老人主档 / 亲情绑定 / 档案授权
-- =============================================================================

CREATE TABLE IF NOT EXISTS `elder` (
    `id`            BIGINT       NOT NULL COMMENT '主键',
    `elder_no`      VARCHAR(32)  NOT NULL COMMENT '老人档案编号（对外可见，不含隐私）',
    `name_enc`      VARCHAR(512) NOT NULL COMMENT '姓名（密文）',
    `name_hash`     CHAR(64)     NULL COMMENT '姓名 HMAC（辅助检索）',
    `id_card_enc`   VARCHAR(512) NULL COMMENT '身份证号（密文，禁止明文）',
    `id_card_hash`  CHAR(64)     NULL COMMENT '身份证号 HMAC-SHA256（精确去重/检索）',
    `id_card_mask`  VARCHAR(24)  NULL COMMENT '脱敏展示串（如 5301**********1234）',
    `gender`        TINYINT      NULL COMMENT '0-女 1-男 2-未知',
    `birth_date`    DATE         NULL COMMENT '出生日期',
    `age`           SMALLINT     NULL COMMENT '年龄（冗余，便于统计）',
    `phone_enc`     VARCHAR(512) NULL COMMENT '手机号（密文）',
    `phone_hash`    CHAR(64)     NULL,
    `address_enc`   VARCHAR(1024) NULL COMMENT '住址（密文）',
    `health_history_enc` TEXT    NULL COMMENT '健康史（密文）',
    `medication_enc`     TEXT    NULL COMMENT '用药记录（密文）',
    `org_id`        BIGINT       NULL COMMENT '所属机构',
    `photo_cos_key` VARCHAR(255) NULL COMMENT '照片对象键',
    `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '0-迁出/死亡 1-在册 2-归档',
    `deleted`       TINYINT      NOT NULL DEFAULT 0,
    `created_by`    BIGINT       NULL,
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_by`    BIGINT       NULL,
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_elder_no` (`elder_no`),
    KEY `idx_id_card_hash` (`id_card_hash`),
    KEY `idx_org_status` (`org_id`, `status`),
    KEY `idx_name_hash` (`name_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='老人档案（敏感字段密文）';

CREATE TABLE IF NOT EXISTS `elder_family_bind` (
    `id`             BIGINT      NOT NULL,
    `elder_id`       BIGINT      NOT NULL,
    `family_user_id` BIGINT      NOT NULL COMMENT '家属账号',
    `relation`       VARCHAR(16) NOT NULL COMMENT 'CHILD/SPOUSE/KIN/GUARDIAN',
    `verify_method`  VARCHAR(16) NOT NULL COMMENT 'SCAN/PHONE/FACE',
    `proof_cos_key`  VARCHAR(255) NULL COMMENT '监护关系证明',
    `auth_start_at`  DATETIME    NULL,
    `auth_end_at`    DATETIME    NULL COMMENT '到期自动解绑（PRD P04）',
    `status`         TINYINT     NOT NULL DEFAULT 0 COMMENT '0-待确认 1-待机构审核 2-生效 3-已拒绝 4-已解绑/过期',
    `reject_reason`  VARCHAR(255) NULL,
    `deleted`        TINYINT     NOT NULL DEFAULT 0,
    -- 仅「未删除」行参与唯一约束：NULL 在唯一索引中互不相等，故同一老人可保留多条历史解绑记录
    `active_uk`      TINYINT GENERATED ALWAYS AS (IF(`deleted` = 0, 1, NULL)) STORED,
    `created_at`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_elder_family` (`elder_id`, `family_user_id`, `active_uk`),
    KEY `idx_family` (`family_user_id`, `status`),
    KEY `idx_expire` (`auth_end_at`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='亲情绑定';

CREATE TABLE IF NOT EXISTS `elder_authorization` (
    `id`            BIGINT      NOT NULL,
    `elder_id`      BIGINT      NOT NULL,
    `grantee_user_id` BIGINT    NOT NULL COMMENT '被授权账号',
    `scope`         VARCHAR(255) NOT NULL COMMENT '授权范围（report/archive/care，逗号分隔）',
    `valid_from`    DATETIME    NULL,
    `valid_to`      DATETIME    NULL,
    `status`        TINYINT     NOT NULL DEFAULT 1 COMMENT '0-已回收 1-生效 2-过期',
    `granted_by`    BIGINT      NULL,
    `revoked_at`    DATETIME    NULL,
    `deleted`       TINYINT     NOT NULL DEFAULT 0,
    `created_at`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_elder` (`elder_id`, `status`),
    KEY `idx_grantee` (`grantee_user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='档案授权（授权/回收/有效期）';

-- =============================================================================
-- 域 4｜评估域（evaluation）：评估任务 / 评估单 / 作答 / 证据 / 复核日志
-- 对齐 PRD §4.4 评估单状态机：草稿→待复核→已发布/已驳回→已作废→归档
-- =============================================================================

CREATE TABLE IF NOT EXISTS `eval_task` (
    `id`          BIGINT      NOT NULL,
    `task_no`     VARCHAR(32) NOT NULL,
    `elder_id`    BIGINT      NOT NULL,
    `org_id`      BIGINT      NOT NULL,
    `assessor_id` BIGINT      NULL COMMENT '指派评估员',
    `scene`       VARCHAR(32) NULL COMMENT '评估场景（居家/机构/社区）',
    `priority`    TINYINT     NOT NULL DEFAULT 0,
    `due_at`      DATETIME    NULL COMMENT '截止时间（催办口径）',
    `status`      TINYINT     NOT NULL DEFAULT 0 COMMENT '0-待接单 1-进行中 2-已完成 3-已取消',
    `deleted`     TINYINT     NOT NULL DEFAULT 0,
    `created_by`  BIGINT      NULL,
    `created_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_no` (`task_no`),
    KEY `idx_assessor_status` (`assessor_id`, `status`),
    KEY `idx_org_status` (`org_id`, `status`),
    KEY `idx_due` (`due_at`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评估任务';

CREATE TABLE IF NOT EXISTS `eval_order` (
    `id`              BIGINT      NOT NULL COMMENT '主键',
    `order_no`        VARCHAR(32) NOT NULL COMMENT '评估单号（对外）',
    `task_id`         BIGINT      NULL,
    `elder_id`        BIGINT      NOT NULL,
    `org_id`          BIGINT      NOT NULL,
    `assessor_id`     BIGINT      NOT NULL COMMENT '录入人',
    `reviewer_id`     BIGINT      NULL COMMENT '复核人（必须≠录入人，互斥）',
    `rule_version_id` BIGINT      NOT NULL COMMENT '本次判定所用国标规则版本',
    `total_score`     DECIMAL(6,2) NULL COMMENT '总分（0-100，分越高能力越好）',
    `grade_code`      VARCHAR(16) NULL COMMENT 'GRADE_INTACT/MILD/MODERATE/SEVERE/TOTAL',
    `grade_upgraded`  TINYINT     NOT NULL DEFAULT 0 COMMENT '是否触发等级上调规则',
    `upgrade_reason`  VARCHAR(255) NULL COMMENT '上调依据（痴呆/精神行为障碍/照护风险事件）',
    `status`          VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
                      COMMENT 'DRAFT/SUBMITTED/PUBLISHED/REJECTED/VOID/ARCHIVED',
    `refuse_count`    SMALLINT    NOT NULL DEFAULT 0 COMMENT '拒绝回答项数（不计分、触发人工复核）',
    `change_reason`   VARCHAR(255) NULL COMMENT '重复评估(30天内)强制填写变更原因',
    `submit_at`       DATETIME    NULL,
    `review_at`       DATETIME    NULL,
    `review_opinion`  VARCHAR(500) NULL COMMENT '复核意见',
    `published_at`    DATETIME    NULL,
    `deleted`         TINYINT     NOT NULL DEFAULT 0,
    `created_at`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY `idx_elder_status` (`elder_id`, `status`),
    KEY `idx_assessor_status` (`assessor_id`, `status`),
    KEY `idx_org_status_time` (`org_id`, `status`, `created_at`),
    KEY `idx_rule_version` (`rule_version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评估单（6 状态机）';

CREATE TABLE IF NOT EXISTS `eval_item` (
    `id`            BIGINT       NOT NULL,
    `rule_version_id` BIGINT     NOT NULL COMMENT '所属规则版本',
    `dimension_code` TINYINT     NOT NULL COMMENT '1-自理能力 2-基础运动能力 3-精神状态 4-感知觉与社会参与',
    `item_code`     VARCHAR(32)  NOT NULL COMMENT '二级指标编码（国标 §4.1 表1，26 项）',
    `item_name`     VARCHAR(64)  NOT NULL COMMENT '二级指标名称',
    `item_desc`     VARCHAR(500) NULL COMMENT '题干/评分说明',
    `score_min`     DECIMAL(4,1) NOT NULL DEFAULT 0,
    `score_max`     DECIMAL(4,1) NOT NULL,
    `weight`        DECIMAL(5,2) NOT NULL DEFAULT 1.00,
    `need_evidence` TINYINT      NOT NULL DEFAULT 0 COMMENT '1-关键指标强制留证',
    `order_no`      SMALLINT     NOT NULL DEFAULT 0,
    `deleted`       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ver_item` (`rule_version_id`, `item_code`),
    KEY `idx_ver_dim` (`rule_version_id`, `dimension_code`, `order_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='指标题库（26 项二级指标）';

CREATE TABLE IF NOT EXISTS `eval_item_option` (
    `id`         BIGINT       NOT NULL,
    `item_id`    BIGINT       NOT NULL,
    `option_code` VARCHAR(16) NOT NULL COMMENT 'A/B/C/D/E',
    `option_text` VARCHAR(255) NOT NULL,
    `score`      DECIMAL(4,1) NOT NULL,
    `order_no`   SMALLINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_item_option` (`item_id`, `option_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='指标选项与赋分';

CREATE TABLE IF NOT EXISTS `eval_answer` (
    `id`          BIGINT       NOT NULL,
    `order_id`    BIGINT       NOT NULL,
    `item_id`     BIGINT       NOT NULL,
    `dimension_code` TINYINT   NOT NULL COMMENT '冗余，便于维度汇总',
    `option_code` VARCHAR(16)  NULL,
    `score`       DECIMAL(4,1) NULL COMMENT '本条得分（拒绝回答为 NULL）',
    `is_refused`  TINYINT      NOT NULL DEFAULT 0 COMMENT '1-拒绝回答（不计分，触发人工复核）',
    `has_evidence` TINYINT     NOT NULL DEFAULT 0,
    `cost_ms`     INT          NULL COMMENT '作答耗时（埋点分析）',
    `answered_at` DATETIME     NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_item` (`order_id`, `item_id`),
    KEY `idx_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评估作答明细';

CREATE TABLE IF NOT EXISTS `eval_evidence` (
    `id`            BIGINT       NOT NULL,
    `order_id`      BIGINT       NOT NULL,
    `item_id`       BIGINT       NULL,
    `evidence_type` VARCHAR(16)  NOT NULL COMMENT 'PHOTO/AUDIO/SIGN/GPS',
    `cos_key`       VARCHAR(255) NOT NULL COMMENT '对象存储键（腾讯云 COS）',
    `sha256`        CHAR(64)     NOT NULL COMMENT '文件摘要，防篡改校验',
    `file_size`     BIGINT       NULL,
    `geo_lat`       DECIMAL(10,7) NULL,
    `geo_lng`       DECIMAL(10,7) NULL,
    `created_by`    BIGINT       NULL,
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_order_item` (`order_id`, `item_id`),
    KEY `idx_sha` (`sha256`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='证据留痕（不可篡改：存 SHA-256）';

CREATE TABLE IF NOT EXISTS `eval_review_log` (
    `id`          BIGINT       NOT NULL,
    `order_id`    BIGINT       NOT NULL,
    `action`      VARCHAR(16)  NOT NULL COMMENT 'SUBMIT/PUBLISH/REJECT/VOID/ARCHIVE',
    `from_status` VARCHAR(16)  NULL,
    `to_status`   VARCHAR(16)  NOT NULL,
    `operator_id` BIGINT       NOT NULL,
    `opinion`     VARCHAR(500) NULL,
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_order_time` (`order_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评估单流转日志（状态机审计）';

-- =============================================================================
-- 域 5｜分级规则域（rule）：国标规则版本 / 维度 / 阈值 / 规则条目 / 条款映射
-- GB/T 42195-2022：4 一级指标 · 26 二级指标 · 总分制 · 5 等级 · 等级上调规则
-- =============================================================================

CREATE TABLE IF NOT EXISTS `gb_dimension` (
    `id`             BIGINT      NOT NULL,
    `rule_version_id` BIGINT     NOT NULL,
    `dimension_code` TINYINT     NOT NULL,
    `dimension_name` VARCHAR(32) NOT NULL COMMENT '自理能力/基础运动能力/精神状态/感知觉与社会参与',
    `clause_no`      VARCHAR(32) NULL COMMENT '国标条款号',
    `order_no`       SMALLINT    NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ver_dim` (`rule_version_id`, `dimension_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='国标一级指标（4 维度）';

CREATE TABLE IF NOT EXISTS `gb_grade_threshold` (
    `id`             BIGINT       NOT NULL,
    `rule_version_id` BIGINT      NOT NULL,
    `grade_code`     VARCHAR(16)  NOT NULL COMMENT 'GRADE_INTACT/MILD/MODERATE/SEVERE/TOTAL',
    `grade_name`     VARCHAR(32)  NOT NULL COMMENT '能力完好/轻度失能/中度失能/重度失能/完全失能',
    `min_score`      DECIMAL(6,2) NOT NULL COMMENT '含',
    `max_score`      DECIMAL(6,2) NOT NULL COMMENT '含',
    `color_key`      VARCHAR(16)  NULL COMMENT '前端色值键（完好绿/轻度黄/中度橙/重度红/完全深红）',
    `order_no`       SMALLINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ver_grade` (`rule_version_id`, `grade_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='等级阈值（5 级，总分制）';

CREATE TABLE IF NOT EXISTS `gb_rule` (
    `id`             BIGINT       NOT NULL,
    `rule_version_id` BIGINT      NOT NULL,
    `rule_code`      VARCHAR(32)  NOT NULL,
    `rule_type`      VARCHAR(16)  NOT NULL COMMENT 'SCORE/GRADE/UPGRADE',
    `dimension_code` TINYINT      NULL,
    `item_code`      VARCHAR(32)  NULL,
    `expression`     TEXT         NOT NULL COMMENT '规则表达式（配置化，非硬编码）',
    `clause_no`      VARCHAR(32)  NULL COMMENT '国标条款号（可追溯）',
    `status`         TINYINT      NOT NULL DEFAULT 1,
    `deleted`        TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ver_rule` (`rule_version_id`, `rule_code`),
    KEY `idx_ver_type` (`rule_version_id`, `rule_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='国标规则条目（配置化引擎数据源）';

CREATE TABLE IF NOT EXISTS `gb_rule_mapping` (
    `id`             BIGINT      NOT NULL,
    `rule_version_id` BIGINT     NOT NULL,
    `clause_no`      VARCHAR(32) NOT NULL COMMENT '国标条款号（如 5.1.1 / 6.2）',
    `clause_title`   VARCHAR(128) NULL,
    `item_code`      VARCHAR(32) NULL COMMENT '映射的二级指标',
    `rule_code`      VARCHAR(32) NULL COMMENT '映射的规则条目',
    `remark`         VARCHAR(255) NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ver_clause_item` (`rule_version_id`, `clause_no`, `item_code`),
    KEY `idx_clause` (`clause_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='国标条款→规则条目映射矩阵（可机器校验的追溯锚点）';

-- =============================================================================
-- 域 6｜报告域（report）：报告 / 维度得分 / 照护模板 / 照护方案
-- =============================================================================

CREATE TABLE IF NOT EXISTS `eval_report` (
    `id`            BIGINT       NOT NULL,
    `report_no`     VARCHAR(32)  NOT NULL COMMENT '报告编号（二维码验真用）',
    `order_id`      BIGINT       NOT NULL,
    `elder_id`      BIGINT       NOT NULL,
    `org_id`        BIGINT       NOT NULL,
    `grade_code`    VARCHAR(16)  NOT NULL,
    `total_score`   DECIMAL(6,2) NOT NULL,
    `pdf_cos_key`   VARCHAR(255) NULL COMMENT 'PDF 对象键',
    `qr_content`    VARCHAR(255) NULL COMMENT '验真二维码内容',
    `verify_hash`   CHAR(64)     NOT NULL COMMENT '报告内容摘要（防篡改验真）',
    `status`        TINYINT      NOT NULL DEFAULT 0 COMMENT '0-生成中 1-有效 2-已作废',
    `generated_at`  DATETIME     NULL,
    `published_at`  DATETIME     NULL,
    `deleted`       TINYINT      NOT NULL DEFAULT 0,
    -- 仅活跃行唯一：一个评估单同时只允许一份有效报告
    `active_uk`     TINYINT GENERATED ALWAYS AS (IF(`deleted` = 0, 1, NULL)) STORED,
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_report_no` (`report_no`),
    UNIQUE KEY `uk_order` (`order_id`, `active_uk`),
    KEY `idx_elder_time` (`elder_id`, `published_at`),
    KEY `idx_org_grade` (`org_id`, `grade_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评估报告';

CREATE TABLE IF NOT EXISTS `report_dimension_score` (
    `id`             BIGINT       NOT NULL,
    `report_id`      BIGINT       NOT NULL,
    `dimension_code` TINYINT      NOT NULL,
    `dimension_name` VARCHAR(32)  NOT NULL,
    `raw_score`      DECIMAL(6,2) NOT NULL COMMENT '维度原始得分',
    `max_score`      DECIMAL(6,2) NOT NULL COMMENT '维度满分',
    `refuse_count`   SMALLINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_report_dim` (`report_id`, `dimension_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报告维度得分（雷达图数据源）';

CREATE TABLE IF NOT EXISTS `care_template` (
    `id`          BIGINT       NOT NULL,
    `grade_code`  VARCHAR(16)  NOT NULL COMMENT '按等级匹配',
    `scene`       VARCHAR(32)  NULL,
    `title`       VARCHAR(128) NOT NULL,
    `content`     TEXT         NOT NULL,
    `sort_no`     SMALLINT     NOT NULL DEFAULT 0,
    `status`      TINYINT      NOT NULL DEFAULT 1,
    `deleted`     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_grade_scene` (`grade_code`, `scene`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='照护建议模板库';

CREATE TABLE IF NOT EXISTS `care_plan` (
    `id`           BIGINT       NOT NULL,
    `report_id`    BIGINT       NOT NULL,
    `elder_id`     BIGINT       NOT NULL,
    `grade_code`   VARCHAR(16)  NOT NULL,
    `items_json`   JSON         NULL COMMENT '照护条目（模板+个性化）',
    `suggest_by`   BIGINT       NULL COMMENT '评估员建议（需管理员确认）',
    `confirmed_by` BIGINT       NULL,
    `confirmed_at` DATETIME     NULL,
    `status`       TINYINT      NOT NULL DEFAULT 0 COMMENT '0-待确认 1-已确认',
    `deleted`      TINYINT      NOT NULL DEFAULT 0,
    `active_uk`    TINYINT GENERATED ALWAYS AS (IF(`deleted` = 0, 1, NULL)) STORED,
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_report` (`report_id`, `active_uk`),
    KEY `idx_elder` (`elder_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='照护方案';

-- =============================================================================
-- 域 7｜监管与报表域（supervise/report）：导出任务 / 监管上报 / 审计 / 埋点
-- =============================================================================

CREATE TABLE IF NOT EXISTS `export_task` (
    `id`            BIGINT       NOT NULL,
    `task_no`       VARCHAR(32)  NOT NULL,
    `export_type`   VARCHAR(32)  NOT NULL COMMENT 'EVAL_LIST/REPORT/STAT',
    `format`        VARCHAR(8)   NOT NULL DEFAULT 'XLSX' COMMENT 'XLSX/PDF/CSV',
    `query_params`  JSON         NULL COMMENT '查询条件快照（可追溯）',
    `mask_level`    TINYINT      NOT NULL DEFAULT 1 COMMENT '脱敏级别 1-标准 2-高敏(需二次验证)',
    `row_count`     INT          NULL COMMENT '导出条数（1 万条预留，超阈值异步）',
    `status`        TINYINT      NOT NULL DEFAULT 0 COMMENT '0-排队 1-生成中 2-完成 3-失败 4-已过期',
    `file_cos_key`  VARCHAR(255) NULL,
    `operator_id`   BIGINT       NOT NULL COMMENT '导出人（PRD §9 必留痕）',
    `started_at`    DATETIME     NULL,
    `finished_at`   DATETIME     NULL,
    `expire_at`     DATETIME     NULL COMMENT '文件过期清理时间',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_export_no` (`task_no`),
    KEY `idx_operator_time` (`operator_id`, `created_at`),
    KEY `idx_status` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='导出任务（异步大导出）';

CREATE TABLE IF NOT EXISTS `supervise_report` (
    `id`            BIGINT       NOT NULL,
    `report_month`  CHAR(7)      NOT NULL COMMENT 'YYYY-MM',
    `org_id`        BIGINT       NOT NULL,
    `payload`       JSON         NOT NULL COMMENT '上报报文',
    `ts_token`      VARCHAR(512) NULL COMMENT '可信时间戳',
    `status`        TINYINT      NOT NULL DEFAULT 0 COMMENT '0-待上报 1-成功 2-失败 3-重试中',
    `retry_count`   SMALLINT     NOT NULL DEFAULT 0,
    `last_error`    VARCHAR(500) NULL,
    `idempotent_key` VARCHAR(64) NOT NULL COMMENT '上报幂等键（PRD 功能18）',
    `sent_at`       DATETIME     NULL,
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_month_org` (`report_month`, `org_id`),
    UNIQUE KEY `uk_idem` (`idempotent_key`),
    KEY `idx_status` (`status`, `retry_count`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='监管上报记录';

CREATE TABLE IF NOT EXISTS `audit_log` (
    `id`             BIGINT       NOT NULL,
    `action`         VARCHAR(32)  NOT NULL COMMENT 'EXPORT/VOID/DELETE/UNBIND/REVIEW/LOGIN...',
    `biz_type`       VARCHAR(32)  NULL,
    `biz_id`         BIGINT       NULL,
    `operator_id`    BIGINT       NULL,
    `operator_name`  VARCHAR(64)  NULL,
    `ip`             VARCHAR(45)  NULL,
    `ua`             VARCHAR(255) NULL,
    `sensitive`      TINYINT      NOT NULL DEFAULT 0 COMMENT '1-敏感操作（需留痕）',
    `second_verify`  TINYINT      NOT NULL DEFAULT 0 COMMENT '是否通过二次验证',
    `detail`         JSON         NULL COMMENT '操作上下文（已脱敏）',
    `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_action_time` (`action`, `created_at`),
    KEY `idx_operator_time` (`operator_id`, `created_at`),
    KEY `idx_biz` (`biz_type`, `biz_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审计日志（敏感操作留痕）';

CREATE TABLE IF NOT EXISTS `idempotent_record` (
    `id`             BIGINT       NOT NULL,
    `idempotent_key` VARCHAR(64)  NOT NULL COMMENT '客户端 Idempotency-Key',
    `user_id`        BIGINT       NULL,
    `api_path`       VARCHAR(128) NOT NULL,
    `request_hash`   CHAR(64)     NULL COMMENT '请求体摘要（防同键不同体）',
    `resp_code`      INT          NULL COMMENT '首次响应码',
    `resp_body`      MEDIUMTEXT   NULL COMMENT '首次响应体（重放直接返回）',
    `status`         TINYINT      NOT NULL DEFAULT 0 COMMENT '0-处理中 1-已完成',
    `expire_at`      DATETIME     NOT NULL COMMENT '过期时间（默认 24h）',
    `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_idem_key` (`idempotent_key`),
    KEY `idx_expire` (`expire_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='幂等键记录（Redis 兜底持久化）';

CREATE TABLE IF NOT EXISTS `track_event` (
    `id`          BIGINT      NOT NULL,
    `event_name`  VARCHAR(64) NOT NULL COMMENT 'PRD §9 八类事件',
    `user_id`     BIGINT      NULL,
    `device_type` VARCHAR(16) NULL,
    `params`      JSON        NULL COMMENT '仅 ID 与枚举值，禁采身份证/人脸原图/健康明细',
    `occurred_at` DATETIME    NOT NULL,
    `expire_at`   DATETIME    NOT NULL COMMENT '保留期 ≤180 天（合规约束）',
    PRIMARY KEY (`id`),
    KEY `idx_event_time` (`event_name`, `occurred_at`),
    KEY `idx_expire` (`expire_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='埋点事件（脱敏，保留≤180天）';

-- =============================================================================
-- 字典播种：国标规则版本 v1 + 4 维度 + 5 等级阈值
-- 26 项二级指标（eval_item）与规则条目（gb_rule）由 C2-1 规则条目化建模导入
-- =============================================================================

INSERT INTO `gb_rule_version` (`id`, `standard_code`, `version`, `status`, `effective_at`, `remark`)
VALUES (1, 'GB/T 42195-2022', 'v1', 1, NOW(), '国标基线版本：4 一级指标/26 二级指标/总分制/5 等级')
ON DUPLICATE KEY UPDATE `updated_at` = CURRENT_TIMESTAMP;

INSERT INTO `gb_dimension` (`id`, `rule_version_id`, `dimension_code`, `dimension_name`, `clause_no`, `order_no`) VALUES
 (1, 1, 1, '自理能力',           'GB/T 42195-2022 §4.1', 1),
 (2, 1, 2, '基础运动能力',       'GB/T 42195-2022 §4.1', 2),
 (3, 1, 3, '精神状态',           'GB/T 42195-2022 §4.1', 3),
 (4, 1, 4, '感知觉与社会参与',   'GB/T 42195-2022 §4.1', 4)
ON DUPLICATE KEY UPDATE `dimension_name` = VALUES(`dimension_name`);

INSERT INTO `gb_grade_threshold` (`id`, `rule_version_id`, `grade_code`, `grade_name`, `min_score`, `max_score`, `color_key`, `order_no`) VALUES
 (1, 1, 'GRADE_INTACT',   '能力完好',   90.00, 100.00, 'green',  1),
 (2, 1, 'GRADE_MILD',     '轻度失能',   66.00,  89.99, 'yellow', 2),
 (3, 1, 'GRADE_MODERATE', '中度失能',   46.00,  65.99, 'orange', 3),
 (4, 1, 'GRADE_SEVERE',   '重度失能',   30.00,  45.99, 'red',    4),
 (5, 1, 'GRADE_TOTAL',    '完全失能',    0.00,  29.99, 'darkred',5)
ON DUPLICATE KEY UPDATE `min_score` = VALUES(`min_score`), `max_score` = VALUES(`max_score`);

INSERT INTO `gb_rule` (`id`, `rule_version_id`, `rule_code`, `rule_type`, `expression`, `clause_no`, `status`) VALUES
 (1, 1, 'RULE_SCORE_SUM',  'SCORE', '总分 = Σ(各二级指标得分)，满分 100',                        'GB/T 42195-2022 §6', 1),
 (2, 1, 'RULE_GRADE_MAP',  'GRADE', '按 gb_grade_threshold 区间映射等级',                        'GB/T 42195-2022 §7', 1),
 (3, 1, 'RULE_UPGRADE',    'UPGRADE', '确诊痴呆(F00-F03)/精神行为障碍(F04-F99)/近30天≥2次照护风险事件 → 原等级上调一级', 'GB/T 42195-2022 §7.2', 1)
ON DUPLICATE KEY UPDATE `expression` = VALUES(`expression`);
