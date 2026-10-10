-- =============================================================================
-- 银龄守护 · 联调/演示账号播种（**仅限 dev / 演示环境**）
--
-- 为什么需要：SecurityConfig 对 /api/v1/** 一律要求认证，而账号域 MVP 只落地了
--   账密渠道（LoginChannel.ANDROID / IOS，passwordSupported=true），
--   短信（R-1 备案未办结）与平台授权（微信/抖音）渠道尚未接入。
--   没有可用账号 → 四端打真实接口必然 401 → 「解除 Mock」验收无法进行。
--   故按 PRD §8「MVP 仅落账密渠道」补两个联调账号。
--
-- ⚠️ 关于「入库」的显式声明（2026-10-10 裁决 P2-8-R9；同日 **P2-8-R12 轮换收口**）：
--   本仓库为 **PUBLIC**。历史事实：本轮之前，本文件注释内**曾写明明文口令**，且四个账号
--   **共用同一个 BCrypt 散列**；该明文与散列随 `62b5e8a..` 已推送的提交**永久留在公开历史**中，
--   无法回收（GitHub 缓存 / fork / 爬虫；重写 PUBLIC main 历史本身是高风险操作，不做）。
--   ⇒ 处置口径：**把旧口令与旧散列一律视为已泄露凭据**，不再赋予任何信任。
--
--   现状（2026-10-10 起）：
--   · 本文件**不含任何明文口令**，也不含任何旧散列；
--   · 四个账号各用**互不相同**的散列（原为同一散列 —— 一处泄露即全部泄露，已修）；
--   · 明文口令只存在于**本机未跟踪文件**中，通过环境变量 `DEV_LOGIN_PASSWORD` 注入
--     （冒烟脚本与文档均以 `${DEV_LOGIN_PASSWORD}` 引用，见 docs/reviews/*-e2e-checklist.md）。
--
--   ★ 真正的结构性防线不是「轮换」（轮换只让当前口令失效），而是
--     `cn.yl.bootstrap.security.DevCredentialStartupGuard`：**启动时若发现已知泄露散列、
--     或非 dev profile 下仍存在 `*_dev` 账号，直接拒绝启动**。这样「未来某次部署静默带着
--     公开口令上线」在结构上不可能发生。
--
--   另注：本文件位于 docker/mysql/init/，镜像初始化会自动执行；生产环境**不得**用本目录做初始化
--   （生产用独立迁移通道），或删除本文件后再初始化。
--
-- 口径：
--   主键      按 ADR-0003 应用层分配（无 AUTO_INCREMENT），此处显式给固定雪花式 id
--   口令      仅存 BCrypt 散列（强度与 PasswordEncoder 默认一致）
--   真实姓名  **不落库**（real_name_enc 属密文列，dev 账号不留明文/密文姓名）
--   幂等      全部 INSERT IGNORE
--   机构归属  **必需**：dataScope 生效后（CR-M2-002）评估员/机构管理员的数据域为「本机构」，
--             orgIds 取自 sys_org_member；缺失即恒假、功能不可用，详见 §3 说明。
-- =============================================================================

USE `yl_evaluation`;

-- ---------- 1) 联调账号：评估员 + 机构管理员（复核人） ----------
-- 口令由本机未跟踪文件提供，经环境变量 DEV_LOGIN_PASSWORD 使用；四账号散列**互不相同**（P2-8-R12）。
INSERT IGNORE INTO `sys_user`
  (`id`, `username`, `password_hash`, `status`, `pwd_updated_at`, `deleted`, `created_at`)
VALUES
  (900000000000000001, 'assessor_dev', '$2a$10$Zq8nJRxPAHU60xZHT3G3aO36bTyXLaNECX8afSiHot/YnSx8TOYvq', 1, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP),
  (900000000000000002, 'orgadmin_dev', '$2a$10$1dVodOkUsfSoRrgcVi8rP.y2RPYPfYJO7u5eEIs/yVvYr4L7ltExG', 1, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP);

-- ---------- 2) 角色绑定（5 角色口径取 03_seed_rbac.sql：1002 ASSESSOR / 1004 ORG_ADMIN） ----------
-- 绑定后 loadProfile 才会带出 roles，JWT 才携带角色声明（复核发布须 ORG_ADMIN「evaluation:order:review」）
INSERT IGNORE INTO `sys_user_role` (`id`, `user_id`, `role_id`) VALUES
  (910000000000000001, 900000000000000001, 1002),
  (910000000000000002, 900000000000000002, 1004);

-- ---------- 3) 联调机构 + 机构成员归属（**CR-M2-002 起为必需**） ----------
-- 为什么必需：dataScope 生效后，评估员/机构管理员（data_scope=2 本机构）的每一次评估域查询都会被
--   第三闸 DataScopePermissionHandler 追加 `org_id IN (orgIds)`；而 orgIds 来自 sys_org_member。
--   若成员关系缺失 → orgIds 为空 → 条件退化为**恒假（1=0）** → 评估员"什么都看不到"（功能不可用），
--   且 UPDATE 会静默影响 0 行（无异常、无报错），排查成本极高。
-- 口径：org 表在 02_schema.sql 只建表未播种，故此处补 1 个联调机构（id=1），与既有 eval_task/eval_order
--   数据的 org_id=1 对齐；成员行的 role_id 与 sys_user_role 保持一致（sys_org_member.role_id NOT NULL）。
INSERT IGNORE INTO `org`
  (`id`, `org_code`, `org_name`, `org_type`, `region_code`, `status`, `deleted`, `created_at`)
VALUES
  (1, 'DEV-ORG-0001', '联调演示机构', 1, '530300', 1, 0, CURRENT_TIMESTAMP);

INSERT IGNORE INTO `sys_org_member`
  (`id`, `org_id`, `user_id`, `role_id`, `is_default`, `status`, `deleted`, `created_at`)
VALUES
  (920000000000000001, 1, 900000000000000001, 1002, 1, 1, 0, CURRENT_TIMESTAMP),
  (920000000000000002, 1, 900000000000000002, 1004, 1, 1, 0, CURRENT_TIMESTAMP);

-- ---------- 4) D-4 家属域联调账号：老人本人 + 家属 ----------
-- elder_dev(…003, ELDER/1001, data_scope=1) + family_dev(…004, FAMILY/1003, data_scope=5)，口令同 §1（经 DEV_LOGIN_PASSWORD 注入）。
-- 用途：
--   · ELDER 段（★C3 覆盖声明）：GET /elders 自读（elder.user_id=me）+ POST /family/bindings/{id}/reject；
--   · FAMILY 段：POST /family/bindings 申请 + GET reports/trend/care-plan + 打 approve 验证 20003（★C1）。
-- ⚠️ elder_dev 与某 elder 的 user_id 归属绑定**经 POST /api/v1/elders/{elderId}/user-binding 端点完成**
--   （设计 §T07 许可的两种方式之一：种子 UPDATE 或经 user-binding 端点）。
--   本种子**不造 elder 行**：elder.name_enc 为 NOT NULL 且禁止明文，而种子阶段没有可用的 dev 敏感字段编解码器，
--   强行写占位值会在 detail/list 解密时抛错（500），故一律由端点在建档后回填，规避"明文落密文列"与解密失败两类风险。
INSERT IGNORE INTO `sys_user`
  (`id`, `username`, `password_hash`, `status`, `pwd_updated_at`, `deleted`, `created_at`)
VALUES
  (900000000000000003, 'elder_dev',  '$2a$10$eW.YoJUXYb5sEl.dHAXD5ezKN9RfAnPD9BAAL8b1FdyFCSa0dRsWO', 1, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP),
  (900000000000000004, 'family_dev', '$2a$10$KBIysPuO8umbuURpjt.tSOFYz/MgfYZeeqygglIz387URSXavIRoS', 1, CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP);

-- 角色绑定：1001 ELDER（老人本人）/ 1003 FAMILY（家属）
INSERT IGNORE INTO `sys_user_role` (`id`, `user_id`, `role_id`) VALUES
  (910000000000000003, 900000000000000003, 1001),
  (910000000000000004, 900000000000000004, 1003);

-- 机构成员：归属联调机构 org=1（role_id 与 sys_user_role 一致；is_default=1）
INSERT IGNORE INTO `sys_org_member`
  (`id`, `org_id`, `user_id`, `role_id`, `is_default`, `status`, `deleted`, `created_at`)
VALUES
  (920000000000000003, 1, 900000000000000003, 1001, 1, 1, 0, CURRENT_TIMESTAMP),
  (920000000000000004, 1, 900000000000000004, 1003, 1, 1, 0, CURRENT_TIMESTAMP);
