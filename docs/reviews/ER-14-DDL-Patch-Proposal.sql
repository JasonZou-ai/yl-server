-- =============================================================================
-- ER-14 DDL 补丁提案 · 第三方账号绑定表
--   ⚠ 状态：**提案，未应用**。须 ER 三方面对《ER-14 提案》§五 五项裁决一致后，
--            方可并入 docker/mysql/init/02_schema.sql，并同步：
--              · scripts/verify-schema.sh  断言 +7 项（绝对计数见合并口径表）
--              · SysUserThirdParty 实体 + Mapper
--              · ThirdPartyBindingResolver 增「按摘要查绑定表」实现
--
-- 依据：docs/reviews/ER-14-third-party-binding-proposal.md
-- 口径：ADR-0003（应用层主键 / DATETIME / 无外键 / deleted + active_uk）
-- 表数：02_schema 36 → 37
--
-- 【PM 裁决 2026-09-21 已填，本补丁按裁决修正 3 处】
--   ① 注销后**保留 30 天**再物理删除 → 新增 `retain_until` 列 + `idx_retain_until` 索引
--   ② 换绑二次验证**分档**（首次短信 / 后续人脸）→ 不涉及本 DDL，落 SecondVerifyGuard
--   ③ 明文禁令**升级为硬否决** → 断言 2 由「建议」升级为发布阻断项
-- =============================================================================

USE `yl_evaluation`;

-- -----------------------------------------------------------------------------
-- 第三方账号绑定：平台身份 ↔ 本服务账号
--   · 只存 HMAC-SHA256 摘要，不存 openid/unionid 明文（PM 硬否决明文方案，对齐 ER-11 与既有承诺）
--   · 双唯一键均带 active_uk：软删后自动释放，支持解绑后重新绑定
--   · retain_until：账号注销后保留 30 天的物理删除时点（PM 裁决 §5.1-A，与 ER-03 机制同构）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_user_third_party` (
    `id`            BIGINT       NOT NULL COMMENT '主键（应用层生成，ADR-0003）',
    `user_id`       BIGINT       NOT NULL COMMENT 'sys_user.id',
    `platform`      VARCHAR(16)  NOT NULL COMMENT '平台：WECHAT/DOUYIN/APPLE',
    `open_id_hash`  CHAR(64)     NOT NULL COMMENT '平台用户标识 HMAC-SHA256（等值检索，不存明文）',
    `union_id_hash` CHAR(64)     NULL COMMENT '开放平台账号标识 HMAC-SHA256（可空）',
    `phone_hash`    CHAR(64)     NULL COMMENT '绑定时手机号 HMAC-SHA256（与 sys_user.phone_hash 同算法）',
    `bound_at`      DATETIME     NOT NULL COMMENT '首次绑定时间',
    `last_login_at` DATETIME     NULL COMMENT '本渠道最近登录时间',
    `deleted`       TINYINT      NOT NULL DEFAULT 0,
    `active_uk`     TINYINT GENERATED ALWAYS AS (IF(`deleted` = 0, 1, NULL)) STORED,
    `retain_until`  DATETIME     NULL COMMENT '物理删除时间 = 账号注销时间 + 30 天（PM 裁决 2026-09-21）',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_platform_openid` (`platform`, `open_id_hash`, `active_uk`),
    UNIQUE KEY `uk_user_platform`   (`user_id`, `platform`, `active_uk`),
    KEY `idx_union_id` (`platform`, `union_id_hash`),
    KEY `idx_phone_hash` (`phone_hash`),
    KEY `idx_retain_until` (`retain_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='第三方账号绑定（ER-14）';

-- -----------------------------------------------------------------------------
-- 断言增补（并入 verify-schema.sh，共 7 项）
-- -----------------------------------------------------------------------------
-- [ER-14 断言 1] 表存在 = 1
-- SELECT COUNT(*) FROM information_schema.tables
--  WHERE table_schema='yl_evaluation' AND table_name='sys_user_third_party';
--
-- [ER-14 断言 2] 明文 open_id / union_id 列必须为 0（⚠ PM 硬否决项，违反即阻断发布）
-- SELECT COUNT(*) FROM information_schema.columns
--  WHERE table_schema='yl_evaluation' AND table_name='sys_user_third_party'
--    AND column_name IN ('open_id','union_id');
--
-- [ER-14 断言 3] open_id_hash 存在且为 CHAR(64)
-- SELECT COUNT(*) FROM information_schema.columns
--  WHERE table_schema='yl_evaluation' AND table_name='sys_user_third_party'
--    AND column_name='open_id_hash' AND data_type='char' AND character_maximum_length=64;
--
-- [ER-14 断言 4/5] 由 ADR-0003 全库断言自动覆盖（TIMESTAMP=0 / 外键=0），
--                  另需把「生成列 active_uk 数量」「deleted 列」「表数」三个计数断言一并上调，
--                  ⚠ 具体绝对值见合并口径表 docs/quality/verify-schema-assertion-reconciliation.md
--                  （不得与 ER-11 v1.1 / CR-M2-001 的增量各自独立累加）。
--
-- [ER-14 断言 6] 行为验证：软删后同 (platform, open_id_hash) 可重绑；
--                未删除时重复绑定须被唯一键真实拒绝。参考既有 elder_family_bind 行为验证写法。
--
-- [ER-14 断言 7] PM 裁决 §5.1-A：保留期列 retain_until 存在（全库计数随本补齐）
-- SELECT COUNT(*) FROM information_schema.columns
--  WHERE table_schema='yl_evaluation' AND table_name='sys_user_third_party'
--    AND column_name='retain_until' AND data_type='datetime';
-- 另断言 idx_retain_until 索引存在 = 1。
