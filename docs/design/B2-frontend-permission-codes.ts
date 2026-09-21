/**
 * 银龄守护 · 权限码常量（前端侧参考实现）
 *
 * 编号 YL-M2-RBAC-CATALOG-v1.0 配套｜编制 2026-09-22｜状态：待四端照抄落地
 *
 * 口径真源（本文件是从属产物，不得反向修改真源）：
 *   1. docker/mysql/init/03_seed_rbac.sql（权威）
 *   2. docs/design/B2-permission-point-catalog.md
 *   3. openapi/yl-api.yaml（契约层面哪些接口已承载哪些码）
 *
 * 使用约定：
 *   - 前端只做「按码显隐 / 按码拦截入口」，不做鉴权判定；真正的鉴权在后端四道闸。
 *   - 任何码的增删须同步真源与本文件，四处对齐（种子 / 后端常量 / 门禁断言 / 契约）。
 *   - UNIMPLEMENTED_PERMS 中的码**禁止发起请求**，只能用于「置灰入口」。
 */

/** 22 个权限码（与种子 sys_permission 一一对应）。 */
export const PERM = {
  // ---- report ----
  REPORT_VIEW: 'report:view',
  DATA_EXPORT: 'data:export', // 敏感：需二次验证

  // ---- evaluation ----
  EVAL_ORDER_CREATE: 'evaluation:order:create',
  EVAL_ORDER_CREATE_APPLY: 'evaluation:order:create:apply',
  EVAL_ITEM_INPUT: 'evaluation:item:input',
  EVAL_ORDER_REVIEW: 'evaluation:order:review',
  EVAL_ORDER_REVIEW_READ: 'evaluation:order:review:read',
  EVAL_ORDER_VOID: 'evaluation:order:void', // 敏感：需二次验证 + 不常驻授权
  EVAL_TASK_READ: 'evaluation:task:read',

  // ---- elder ----
  ELDER_ARCHIVE_CREATE: 'elder:archive:create',
  ELDER_ARCHIVE_CREATE_APPLY: 'elder:archive:create:apply',
  ELDER_ARCHIVE_READ: 'elder:archive:read',

  // ---- care ----
  CARE_PLAN_EDIT: 'care:plan:edit',
  CARE_PLAN_EDIT_SUGGEST: 'care:plan:edit:suggest',

  // ---- account ----
  ACCOUNT_ORG_MANAGE: 'account:org:manage',
  ACCOUNT_ORG_MANAGE_READ: 'account:org:manage:read',
  ACCOUNT_FAMILY_BIND: 'account:family:bind',
  ACCOUNT_FAMILY_BIND_REJECT: 'account:family:bind:reject',
  ACCOUNT_FAMILY_UNBIND: 'account:family:unbind', // 敏感：需二次验证 + 不常驻授权

  // ---- supervise ----
  SUPERVISE_REPORT_SUBMIT: 'supervise:report:submit',

  // ---- security ----
  DATA_REVEAL: 'data:reveal', // 敏感：需二次验证

  // ---- rule ----
  RULE_VIEW: 'rule:view',
} as const;

export type PermissionCode = (typeof PERM)[keyof typeof PERM];

/**
 * 尚未有后端接口承载的权限码（契约反向缺口，**共 8 个码 / 6 类接口**）。
 *
 * ⚠ 口径纠偏：清单 §5.2 记作「6 类」，是按**接口维度**归类（bind 与 reject 同属 /family 一类、
 * care 两个码同属 /care/plans 一类、org 两个码同属 /account/orgs 一类）；落到**权限码维度是 8 个**。
 * 前端标注须按 8 个码逐一标，否则会漏掉 :reject / :suggest / :read 这三个「同路径不同码」的点。
 *
 * 处置：这些码只能用于**置灰入口 / 隐藏入口**，不得据其发起请求；接口随 C/D 模块补登后（契约
 * 21 路径/22 操作 → 28 路径/29 操作）再从本表移除。
 */
export const UNIMPLEMENTED_PERMS = [
  PERM.EVAL_ORDER_VOID, // 待补 POST /eval/orders/{orderId}/void（敏感）
  PERM.ACCOUNT_FAMILY_UNBIND, // 待补 POST /family/unbind（敏感）
  PERM.ACCOUNT_FAMILY_BIND, // 待补 POST /family/bind
  PERM.ACCOUNT_FAMILY_BIND_REJECT, // 待补 POST /family/bind/{id}/reject
  PERM.CARE_PLAN_EDIT, // 待补 PUT /care/plans/{planId}
  PERM.CARE_PLAN_EDIT_SUGGEST, // 待补 PUT /care/plans/{planId}（受限态）
  PERM.ACCOUNT_ORG_MANAGE, // 待补 GET /account/orgs 写侧
  PERM.ACCOUNT_ORG_MANAGE_READ, // 待补 GET /account/orgs、/account/orgs/{orgId}
] as const;

export type UnimplementedPermissionCode = (typeof UNIMPLEMENTED_PERMS)[number];

/** 4 个敏感权限码：命中即需走第四闸二次验证（X-Second-Verify-Token）。 */
export const SENSITIVE_PERMS = [
  PERM.DATA_EXPORT,
  PERM.EVAL_ORDER_VOID,
  PERM.ACCOUNT_FAMILY_UNBIND,
  PERM.DATA_REVEAL,
] as const;

export type SensitivePermissionCode = (typeof SENSITIVE_PERMS)[number];

/**
 * 判定入口是否可用。
 *
 * @param granted 当前用户被授予的权限码（登录态下发）
 * @param required 该入口所需权限码
 * @returns true = 可发起请求；false = 应置灰/隐藏
 */
export function canUse(granted: readonly string[], required: PermissionCode): boolean {
  // 未实现的码：即使被授予也不可发起请求（后端无接口，调了必 404）
  if ((UNIMPLEMENTED_PERMS as readonly string[]).includes(required)) {
    return false;
  }
  return granted.includes(required);
}

/** 是否需要二次验证（用于提前弹出验证入口，避免请求打回 10008）。 */
export function needsSecondVerify(required: PermissionCode): boolean {
  return (SENSITIVE_PERMS as readonly string[]).includes(required);
}
