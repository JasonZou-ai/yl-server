-- 银龄守护 · 本地开发库初始化
-- 生产库结构由 Flyway 迁移脚本管理（src/main/resources/db/migration），此处仅建库与基础配置。
-- 敏感字段（身份证号/人脸特征）以密文存储，密钥由 KMS/环境变量注入，禁止明文落库。

CREATE DATABASE IF NOT EXISTS `yl_evaluation`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE `yl_evaluation`;

-- 预留：国标规则版本表（GB/T 42195-2022 规则可版本化、可热更、可回溯）
CREATE TABLE IF NOT EXISTS `gb_rule_version` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `standard_code` VARCHAR(32)  NOT NULL DEFAULT 'GB/T 42195-2022' COMMENT '标准号',
    `version`       VARCHAR(32)  NOT NULL COMMENT '规则版本号',
    `status`        TINYINT      NOT NULL DEFAULT 0 COMMENT '0-草稿 1-生效 2-已下线',
    `effective_at`  DATETIME     NULL COMMENT '生效时间',
    `remark`        VARCHAR(255) NULL COMMENT '备注',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code_version` (`standard_code`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='国标规则版本表';
