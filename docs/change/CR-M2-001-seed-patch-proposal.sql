-- =============================================================================
-- CR-M2-001 种子补丁提案 · 权限矩阵缺口补齐
--   ✅ 状态：**已应用（2026-09-21）**。CCB 会签 + PM 签发通过后，正文已并入
--            docker/mysql/init/03_seed_rbac.sql 第 4 节，并同步 verify-schema.sh
--            （权限点 22 / 授权 40 / 敏感点 4 / 断言总数 60）。
--   ⚠ 本文件保留为变更留痕，**请勿重复执行**（正文与已落地内容一致；重复执行因 INSERT IGNORE 无害）。
--
-- 依据：docs/change/CR-M2-001-permission-matrix-gap.md
--       docs/design/B2-permission-api-matrix.md（7 处 pending-cr 的来源）
--
-- 变更量：sys_permission 16 → 22（+6）；sys_role_permission 23 → 40（+17）；
--         敏感点 3 → 4（新增 data:reveal）
-- 幂等：全部 INSERT IGNORE，可重复执行。
-- =============================================================================

USE `yl_evaluation`;

-- ---------- 1) 新增权限点（id 2017–2022） ----------
INSERT IGNORE INTO `sys_permission` (`id`, `perm_code`, `perm_name`, `module`, `need_second_verify`, `deleted`) VALUES
  -- 新增矩阵行 10 老人建档
  (2017, 'elder:archive:create',       '老人建档',                'elder',      0, 0),
  (2018, 'elder:archive:create:apply', '发起代办建档申请（受限）', 'elder',      0, 0),
  -- 新增矩阵行 11 查看老人档案
  (2019, 'elder:archive:read',         '查看老人档案',            'elder',      0, 0),
  -- 新增矩阵行 12 评估任务列表查看
  (2020, 'evaluation:task:read',       '评估任务列表查看',        'evaluation', 0, 0),
  -- 新增矩阵行 13 查看敏感字段明文（敏感：须二次验证 + 留痕）
  (2021, 'data:reveal',                '查看敏感字段明文',        'security',   1, 0),
  -- 新增矩阵行 14 国标规则只读（条款回溯）
  (2022, 'rule:view',                  '国标规则只读（条款回溯）', 'rule',       0, 0);

-- ---------- 2) 新增授权（id 3024–3040，共 17 条） ----------
-- 角色：1001 ELDER / 1002 ASSESSOR / 1003 FAMILY / 1004 ORG_ADMIN / 1005 SUPERVISOR
INSERT IGNORE INTO `sys_role_permission` (`id`, `role_id`, `perm_id`) VALUES
  -- 老人建档：评估员✅ 机构管理员✅ 家属🔶(代办申请)
  (3024, 1002, 2017), (3025, 1004, 2017), (3026, 1003, 2018),
  -- 查看老人档案：五角色全授予（可见范围由 data_scope 控制，不分裂权限点）
  (3027, 1001, 2019), (3028, 1002, 2019), (3029, 1003, 2019), (3030, 1004, 2019), (3031, 1005, 2019),
  -- 评估任务列表：评估员✅ 机构管理员✅ 监管👁
  (3032, 1002, 2020), (3033, 1004, 2020), (3034, 1005, 2020),
  -- 查看敏感字段明文：仅机构管理员✅（敏感，须二次验证）
  (3035, 1004, 2021),
  -- 国标规则只读：五角色全授予（登录即可读）
  (3036, 1001, 2022), (3037, 1002, 2022), (3038, 1003, 2022), (3039, 1004, 2022), (3040, 1005, 2022);

-- ---------- 3) 自检（应用后应为 5 / 22 / 40，敏感点 4） ----------
-- SELECT (SELECT COUNT(*) FROM sys_role)                                              AS roles_expected_5,
--        (SELECT COUNT(*) FROM sys_permission)                                        AS perms_expected_22,
--        (SELECT COUNT(*) FROM sys_role_permission)                                   AS grants_expected_40,
--        (SELECT COUNT(*) FROM sys_permission WHERE need_second_verify = 1)            AS sensitive_expected_4;

-- ---------- 4) 新增 ❌ 红线（须同步为 verify-schema.sh 断言，均应为 0） ----------
-- SELECT COUNT(*) AS must_be_zero FROM sys_role_permission rp
--   JOIN sys_role r ON r.id = rp.role_id JOIN sys_permission p ON p.id = rp.perm_id
--  WHERE (r.role_code = 'ELDER'      AND p.perm_code = 'elder:archive:create')
--     OR (r.role_code = 'SUPERVISOR' AND p.perm_code = 'elder:archive:create')
--     OR (r.role_code IN ('ELDER','ASSESSOR','FAMILY','SUPERVISOR') AND p.perm_code = 'data:reveal')
--     OR (r.role_code IN ('ELDER','FAMILY') AND p.perm_code = 'evaluation:task:read');
