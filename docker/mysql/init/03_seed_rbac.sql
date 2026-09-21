-- =============================================================================
-- 银龄守护 · RBAC 基础数据播种（B2 任务组）
--
-- 真源：PRD v1.1 §2.1 角色定义 + §2.2 权限矩阵（唯一验收基准，逐格可回溯）
-- 关联：文档 docs/design/B2-account-rbac-design.md
-- 前置：必须先执行 01_init.sql、02_schema.sql（本文件只 INSERT，不建表）
--
-- 口径约定：
--   sys_role.data_scope  : 1-本人 2-本机构 3-全量 4-只读全局
--   sys_permission.need_second_verify : 1 表示该权限点为敏感操作，须二次验证并留痕
--                                        （对应 audit_log.sensitive / second_verify）
--   权限码命名          : <module>:<resource>:<action>[:受限后缀]
--                          受限/需审批 → 后缀 :apply / :suggest
--                          只读        → 后缀 :read
--   角色码              : ELDER / ASSESSOR / FAMILY / ORG_ADMIN / SUPERVISOR（五角色，不得增删）
--
-- 幂等：全部使用 INSERT IGNORE，可重复执行。
-- =============================================================================

USE `yl_evaluation`;

-- ---------- 1) 五角色（PRD §2.1） ----------
INSERT IGNORE INTO `sys_role` (`id`, `role_code`, `role_name`, `data_scope`, `deleted`, `created_at`) VALUES
  (1001, 'ELDER',      '老人/被评估人', 1, 0, CURRENT_TIMESTAMP),
  (1002, 'ASSESSOR',   '评估员',       2, 0, CURRENT_TIMESTAMP),
  (1003, 'FAMILY',     '家属',         1, 0, CURRENT_TIMESTAMP),
  (1004, 'ORG_ADMIN',  '机构管理员',   2, 0, CURRENT_TIMESTAMP),
  (1005, 'SUPERVISOR', '监管',         4, 0, CURRENT_TIMESTAMP);

-- ---------- 2) 权限点（PRD §2.2 矩阵 9 行展开为 14 个原子权限 + 2 个敏感操作权限点 = 16） ----------
INSERT IGNORE INTO `sys_permission` (`id`, `perm_code`, `perm_name`, `module`, `need_second_verify`, `deleted`) VALUES
  -- 行1 查看评估报告
  (2001, 'report:view',                     '查看评估报告',            'report',     0, 0),
  -- 行2 新建评估
  (2002, 'evaluation:order:create',         '新建评估',                'evaluation', 0, 0),
  (2003, 'evaluation:order:create:apply',   '发起代办评估申请（受限）', 'evaluation', 0, 0),
  -- 行3 录入评估指标
  (2004, 'evaluation:item:input',           '录入评估指标',            'evaluation', 0, 0),
  -- 行4 复核/发布报告
  (2005, 'evaluation:order:review',         '复核/发布报告',           'evaluation', 0, 0),
  (2006, 'evaluation:order:review:read',    '查看复核结果（只读）',     'evaluation', 0, 0),
  -- 行5 编辑照护方案
  (2007, 'care:plan:edit',                  '编辑照护方案',            'care',       0, 0),
  (2008, 'care:plan:edit:suggest',          '提交照护建议（受限）',     'care',       0, 0),
  -- 行6 机构/人员管理
  (2009, 'account:org:manage',              '机构/人员管理',           'account',    0, 0),
  (2010, 'account:org:manage:read',         '查看机构/人员（只读）',    'account',    0, 0),
  -- 行7 监管数据上报
  (2011, 'supervise:report:submit',         '监管数据上报',            'supervise',  0, 0),
  -- 行8 导出/批量操作（敏感，需二次验证）
  (2012, 'data:export',                     '导出/批量操作',           'report',     1, 0),
  -- 行9 亲情绑定/解绑
  (2013, 'account:family:bind',             '亲情绑定/解绑',           'account',    0, 0),
  (2014, 'account:family:bind:reject',      '拒绝亲情绑定（老人端）',   'account',    0, 0),
  -- 附：敏感操作权限点（非矩阵行，但须二次验证留痕）
  (2015, 'evaluation:order:void',           '作废评估单',              'evaluation', 1, 0),
  (2016, 'account:family:unbind',           '解绑亲情关系',            'account',    1, 0);

-- ---------- 3) 角色-权限授权（✅=授予；🔶受限=授予受限权限；👁只读=授予只读权限） ----------
INSERT IGNORE INTO `sys_role_permission` (`id`, `role_id`, `perm_id`) VALUES
  -- 行1 查看评估报告：老人/评估员/家属/机构管理员/监管 全部 ✅
  (3001, 1001, 2001), (3002, 1002, 2001), (3003, 1003, 2001), (3004, 1004, 2001), (3005, 1005, 2001),
  -- 行2 新建评估：评估员✅ 机构管理员✅ 家属🔶(代办申请)
  (3006, 1002, 2002), (3007, 1004, 2002), (3008, 1003, 2003),
  -- 行3 录入评估指标：评估员✅ 机构管理员✅
  (3009, 1002, 2004), (3010, 1004, 2004),
  -- 行4 复核/发布报告：机构管理员✅ 监管👁(只读)
  (3011, 1004, 2005), (3012, 1005, 2006),
  -- 行5 编辑照护方案：机构管理员✅ 评估员🔶(建议)
  (3013, 1004, 2007), (3014, 1002, 2008),
  -- 行6 机构/人员管理：机构管理员✅ 监管👁(只读)
  (3015, 1004, 2009), (3016, 1005, 2010),
  -- 行7 监管数据上报：机构管理员✅ 监管✅
  (3017, 1004, 2011), (3018, 1005, 2011),
  -- 行8 导出/批量操作：机构管理员✅ 监管✅（均需二次验证）
  (3019, 1004, 2012), (3020, 1005, 2012),
  -- 行9 亲情绑定/解绑：家属✅ 机构管理员✅ 老人🔶(可拒绝)
  (3021, 1003, 2013), (3022, 1004, 2013), (3023, 1001, 2014);
  -- 注：敏感操作权限点 2015(作废) / 2016(解绑) 默认不授予任何角色，
  --     由机构管理员在运行时经二次验证后临时提权（避免权限常驻，PRD §2.2 备注）。

-- ---------- 4) 自检（可选，正常返回 5 / 16 / 23） ----------
-- SELECT (SELECT COUNT(*) FROM sys_role)             AS roles_expected_5,
--        (SELECT COUNT(*) FROM sys_permission)       AS perms_expected_16,
--        (SELECT COUNT(*) FROM sys_role_permission)  AS grants_expected_23;
