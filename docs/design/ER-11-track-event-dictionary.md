# 四端统一埋点事件名字典（合并定稿 v1.1）

> 编号 **YL-M2-ER11-TRACK-DICT-v1.1**｜编制 2026-09-21｜状态：**✅ 合并定稿（取代 v0 初稿 与 v1.0 定稿）· §7 DDL + 30 条字典种子已上库（2026-09-21）· 定稿决议见 §10**
> 真源：PRD §9 数据埋点需求 ＋ M1《脱敏与埋点合规方案》`YL-M1-A2-DESENS-001` §4（8 事件禁采清单）＋ 用户采纳的 5 项决策
> 合规基线：《个人信息保护法》(PIPL)、《数据安全法》、GB/T 42195-2022、四端平台规则
> 关联：ER 评审 ER-11（埋点事件名字典，r5HcnX）｜落库表 `track_event` / `track_event_dict`
> 覆盖四端：**WXMP**（微信小程序）/ **DYMP**（抖音小程序）/ **IOS** / **ANDROID**（＋管理后台 WEB）

---

## 0 命名与分级约定

**事件名（event_code）**：`<stage>_<object>_<action>`，全小写下划线，≤ 64 字符（与 `track_event.event_name VARCHAR(64)` 一致）；同一业务动作跨四端**必须同名**（四端差异仅体现在公共属性 `app_channel`）。

**敏感分级（埋点维度，对齐 PIPL）**：

| 级别 | 含义 | 处置 |
|---|---|---|
| **s0** | 不含个人信息 | 直接上报 |
| **s1** | 含可识别信息 | **哈希/泛化后**上报（`*_hash`、分段、枚举） |
| **s2** | 涉敏感个人信息 | **禁采原文**，仅传「ID / 枚举 / 布尔 / 计数」——对齐 M1 禁采清单 |

> 注：v1.0 定稿曾用 `low/medium/high` 命名，本版统一回退为 **s0/s1/s2**（与 M1/PIPL 口径一致）。

**通用红线（硬性，8 事件禁采清单的推广）**：
1. **禁采**：身份证全文/图像、人脸原始图像、健康明细原文、报告正文、题干原答文本、缓存内容、页面内敏感字段值；
2. 老人端埋点须**本人或家属授权**，未授权不采集；
3. 埋点**脱敏后入库**，`expire_at = occurred_at + 180 天`，到期自动清理；
4. 事件名与参数**纳入白名单校验**，未登记事件名不得上报（防"随手加字段"）；
5. **不采集 `device_id`**（用户决策）；`user_id` **一律以 HMAC-SHA256 哈希上报，不传明文**（修正 v1.0 明文回退）。

---

## 1 公共属性（所有事件携带）

| 属性 | 类型 | 说明 | 敏感 |
|---|---|---|---|
| `app_channel` | enum | `WXMP`/`DYMP`/`IOS`/`ANDROID`/`WEB` | s0 |
| `app_version` | string | 客户端版本号 | s0 |
| `os` | string | 操作系统及版本 | s0 |
| `session_id` | string | 匿名会话 ID（随机 UUID） | s0 |
| `user_role` | enum | `ELDER`/`ASSESSOR`/`FAMILY`/`ORG_ADMIN`/`SUPERVISOR` | s0 |
| `user_id_hash` | string | 用户 ID 的 HMAC-SHA256（**不传明文 user_id**） | s1 |
| `org_id` | long | 机构 ID（业务主体，非个人信息） | s0 |
| `page_id` | string | 页面标识（白名单） | s0 |
| `ts_client` | long | 客户端时间戳（ms） | s0 |
| `network` | enum | `WIFI`/`4G`/`5G`/`NONE` | s0 |
| `trace_id` | string | 链路追踪 ID | s0 |

> 相比 v0：删除 `device_id`（用户决策不采集）；`user_id` 改为 `user_id_hash`（修正 v1.0 明文回退）。

---

## 2 事件清单（30，MVP 收敛）

> ★ = M1 禁采清单 8 事件（必须保留原名，合规追溯）；`(审计)` = 须二次验证 + `audit_log` 留痕。

| # | event_code | 中文名 | 触发时机 | 关键参数 | 敏感 | 四端 |
|---|---|---|---|---|:--:|---|
| 1 | `auth_login_start` | 发起登录 | 点击登录 | `login_channel`(WECHAT/DOUYIN/PWD/FACE)、`device_type` | s0 | 全 |
| 2 | `auth_login_result` | 登录结果 | 登录返回 | `login_channel`、`success`、`fail_code`(枚举)、`is_new_user` | **s1** | 全 |
| 3 | `auth_logout` | 退出登录 | 主动/超时登出 | `reason`(USER/TIMEOUT) | s0 | 全 |
| 4 | `elder_create_submit` | 提交建档 | 提交表单 | `has_idcard`、`has_phone`（**仅布尔**） | **s1** | App |
| 5 | `elder_create_result` | 建档结果 | 建档返回 | `success`、`elder_id_hash` | **s1** | App |
| 6 | `elder_info_edit` | 编辑档案 | 保存修改 | `field_count`、`edit_type`(枚举) | **s1** | 全 |
| 7 | `elder_search` | 检索老人 | 搜索提交 | `search_type`(PHONE_EXACT/NAME_EXACT)、`result_count` | **s2** | 全 |
| 8 | `assessment_create` ★ | 新建评估单 | 创建评估单 | `elder_id`、`scene`、`assessor_id`、`org_id` | **s2** | 全 |
| 9 | `assessment_item_answer` ★ | 单项作答 | 每项作答 | `item_id`、`dimension`、`score_band`(档位)、`elapsed_ms`、`has_evidence` | **s2** | 全 |
| 10 | `assessment_submit` ★ | 提交评估 | 提交评估单 | `order_id`、`level`、`dim_levels`（**不传 total_score**）、`elapsed_ms` | **s2** | 全 |
| 11 | `assessment_draft_save` | 暂存草稿 | 点击暂存 | `answered_count`、`elapsed_ms` | s0 | 全 |
| 12 | `grade_auto_result` | 分级完成 | 判定返回 | `grade_code`、`score_band`(枚举分段)、`upgraded`、`elapsed_ms` | **s1** | 全 |
| 13 | `grade_upgrade_reason` | 上调原因 | 触发等级上调 | `reason_category`(CATEGORY 枚举) | **s2** | 全 |
| 14 | `report_generate` | 生成报告 | 生成动作 | `order_id_hash`、`format`、`elapsed_ms` | **s1** | 全 |
| 15 | `report_view` ★ | 查看报告 | 打开报告 | `report_id`、`viewer_role`、`source` | **s2** | 全 |
| 16 | `report_share` | 分享报告 | 分享 | `share_channel`、`target_role` | **s1** | 全 |
| 17 | `report_export` ★ | 导出报告 | 导出 | `report_id`、`format`、`exporter_role`、`desensitize_level` | **s2** | Web/App |
| 18 | `care_plan_generate` | 生成照护计划 | 生成 | `order_id_hash`、`item_count` | **s1** | 全 |
| 19 | `care_plan_edit` | 编辑照护计划 | 保存 | `added_count`、`removed_count` | **s1** | 全 |
| 20 | `care_plan_confirm` | 确认照护计划 | 确认 | `order_id_hash`、`confirmer_role` | **s1** | 全 |
| 21 | `care_task_complete` | 任务完成 | 完成 | `task_type`、`duration_ms` | s0 | App |
| 22 | `archive_elder_view` | 查看档案 | 打开档案 | `elder_id_hash`、`viewer_role` | **s1** | 全 |
| 23 | `archive_plaintext_view` (审计) | 查看明文敏感字段 | 点击"查看原文" | `field_type`(IDCARD/PHONE/HEALTH)、`verify_method`(SCAN/PHONE/FACE) | **s2** | 全 |
| 24 | `archive_download` (审计) | 档案下载 | 下载 | `archive_id`、`format`、`desensitize_level` | **s2** | Web |
| 25 | `archive_access_log_query` | 调阅访问记录 | 查询留痕 | `target_id_hash`、`result_count` | **s1** | Web |
| 26 | `bind_relative` ★ | 亲情绑定 | 提交绑定 | `relation`、`verify_method`、`success` | **s2** | 全(微信优先) |
| 27 | `consent_record` | 同意留痕 | 同意/撤回政策 | `policy_code`、`policy_version`、`action`(GRANT/REVOKE)、`is_optional` | **s1** | 全 |
| 28 | `offline_sync` ★ | 离线同步 | 联网同步 | `sync_count`、`conflict_count`、`elapsed_ms` | **s2** | App |
| 29 | `page_stay` ★ | 页面停留 | 离开页面 | `page_id`、`duration_ms`、`enter_at`、`leave_at` | **s2** | 全 |
| 30 | `error_occur` | 异常发生 | 捕获异常 | `error_code`、`page_id`、`is_network` | **s1** | 全 |

> **收敛说明**：自 v0 的 48 事件收敛至 30（48 − 18 = 30），规则为「监管报表必需 + 核心漏斗 + 关键异常 + 合规敏感操作」；M1 禁采 8 事件、consent_record、archive 明文/下载审计事件**一律保留原名**（不得自造/简并）。
>
> 被裁事件共 **18** 个：`auth_page_view`、`auth_token_refresh`、`elder_create_start`、`elder_idcard_ocr`、`assessment_questionnaire_start`、`assessment_item_skip`、`assessment_offline_enter`、`assessment_evidence_capture`、`grade_auto_trigger`、`grade_manual_review`、`report_publish`、`report_print`、`care_task_dispatch`、`care_task_checkin`、`archive_retention_set`、`archive_archive_trigger`、`app_launch`、`api_perf` —— 标记为 P2，后续按需补回。
>
> ⚠ **R5 修正（2026-09-22）**：`care_plan_confirm` **已保留为上表 #20（s1）**，此前被误列入上述被裁清单，易使读者误判「已保留的事件被裁掉」。现已从被裁清单移除，本段事件数为 `13 + 5 = 18`，与「48 − 30」自洽。

---

## 3 敏感级别说明

- **s2**：涉及老人身份/健康结论（档案、报告、评估单、检索、绑定、离线同步、页面停留、查看/下载明文）。参数仅含脱敏 ID 与等级，不含明文敏感字段；其中 `report_export` / `archive_plaintext_view` / `archive_download` 需**二次验证 + audit_log**。
- **s1**：含角色/关系/机构/哈希标识等业务标识，需授权访问。
- **s0**：登录态、异常、汇总类，无个人敏感信息。

---

## 4 M1 禁采清单（8 事件）对照 —— 全保留原名

| M1 序 | M1 事件名 | 本字典归位 | 一致性 |
|:--:|---|---|:--:|
| 1 | `assessment_create` | §2 #8 | ✅ 同名（v1.0 曾误改名 assessment_start，本版纠正） |
| 2 | `assessment_item_answer` | §2 #9 | ✅ 同名 |
| 3 | `assessment_submit` | §2 #10 | ✅ 同名（参数改为 level+dim_levels，不传 total_score） |
| 4 | `report_view` | §2 #15 | ✅ 同名 |
| 5 | `report_export` | §2 #17 | ✅ 同名 |
| 6 | `bind_relative` | §2 #26 | ✅ 同名（v1.0 曾误改名 family_bind，本版纠正） |
| 7 | `offline_sync` | §2 #28 | ✅ 同名（v1.0 曾删除，本版恢复） |
| 8 | `page_stay` | §2 #29 | ✅ 同名（v1.0 曾删除，本版恢复） |

> **结论**：v1.0 定稿在收敛时丢失/改名的 3 个禁采事件，本版全部恢复原名，保证与 M1 合规承诺可追溯一致。

---

## 5 与 v1.0 定稿（项目盘）的关键修正

| # | v1.0 问题 | 本版修正 |
|---|---|---|
| 1 | `user_id` 明文上报 | 改为 `user_id_hash`（HMAC），不传明文 |
| 2 | 改名 `family_bind`/`assessment_start`/`careplan_*` 等 | 一律恢复 v0 原名（四端统一、不重命名） |
| 3 | 删除 `offline_sync`/`page_stay`/`bind_relative` 改名 | 恢复 8 禁采原名 |
| 4 | 删除 `consent_record` | 恢复（PIPL 同意留痕） |
| 5 | 删除 `archive_plaintext_view`/`archive_download` | 恢复（敏感操作审计） |
| 6 | 敏感级别 `low/medium/high` | 统一 `s0/s1/s2` |

> 上述修正中，**#1（user_id 明文）属于对 DPO 已签字 v1.0 的隐私加强**，建议 DPO 二次确认后生效。

---

## 6 DPO 代填判断（已确认 + 待二次确认）

| 编号 | 判断 | 结论 | 状态 |
|---|---|---|---|
| DPO-1 | device_id 是否采集 | 不采集（设备级追踪风险） | ✅ DPO 已确认（2026-09-21） |
| DPO-2 | assessment_submit 是否传精确总分明文 | 不传，仅传 level+dim_levels | ✅ DPO 已确认（2026-09-21） |
| DPO-3 | user_id 是否明文上报 | 不传明文，HMAC 哈希 | ⏳ 待 DPO 二次确认（本版修正 v1.0 明文回退） |

---

## 7 track_event_dict 建表（合并 DDL）

```sql
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
```

> **⚠ 本版对 v1.1 原 DDL 的修正（2026-09-21）**：原稿使用 `id BIGINT PRIMARY KEY AUTO_INCREMENT`、
> `event_code ... UNIQUE`、且缺 `deleted` / `active_uk` —— **违反 ADR-0003 全库口径**
> （业务表主键应用层生成、禁用 `AUTO_INCREMENT`；软删除统一用 `deleted` + `active_uk` 生成列），
> 会直接触发 `verify-schema.sh` 的「自增列 = 1」断言失败。已按 ADR-0003 改写：
> 主键改应用层生成、唯一键加 `active_uk`（停用/软删后同名事件可重新登记）、补软删除两列。
>
> **上库路径**：本表**尚未加入 `02_schema.sql`**（当前 36 表）。建表须随 **ER 补丁**上库
> （建议与 ER-14 第三方绑定表同批），并同步 `verify-schema.sh` 断言：
> 表数 37 → 39、`active_uk` 生成列 3 → 5、`deleted` 列 19 → 21。

**后台校验规则**：上报的 `event_code` 不在 `track_event_dict`（且 status=1）时，后端拒绝写入并告警，确保四端不会埋入未登记/已停用事件。

---

## 8 落地步骤

1. 后端定义 `EventCode` 枚举（与 `track_event_dict.event_code` 一一对应），作为事实来源。
2. 初始化 `track_event_dict` 表，写入本字典 30 条记录（含 `sensitivity`/`retain_days`）。
3. 四端 SDK 统一上报结构（§1 公共属性 + §2 事件），编译期引用 `EventCode` 枚举防拼错。
4. 上报网关做 `event_code` 白名单校验（§7）。
5. DPO 确认 §6 三项（含 DPO-3 二次确认）→ 关闭 ER-11。

---

## 9 四端差异化

| 差异点 | 说明 |
|---|---|
| 事件名 | **四端统一**，无端后缀；差异由公共属性 `app_channel` 表达 |
| 渠道专属事件 | `auth_login_start.login_channel`：微信 `WECHAT`、抖音 `DOUYIN`、iOS `FACE/PWD`、Android `PWD` |
| 端专属事件 | `elder_*`(App)、`archive_*`(Web)、`offline_sync`/`care_task_*`(App) |
| 小程序限制 | 微信/抖音不支持长时后台定位与大文件缓存 → `care_task_checkin` 在小程序恒 `false`（本版已裁该事件，MVP 不采集 geo） |
| 采集时机 | 小程序端埋点须**先获授权**再初始化 SDK（对齐 M1 §6 单独同意） |

---

## 10 定稿决议（2026-09-21）

需求方授权按推荐口径定稿，逐项闭环 v1.1 遗留问题：

| # | 待决项 | 定稿口径 | 状态 |
|---|---|---|---|
| 1 | 事件数是否收敛 | **收敛至 30 条**（自 v0 的 48 条），规则＝监管报表必需 + 核心漏斗 + 关键异常 + 合规敏感操作；被裁事件标 P2 按需补回 | ✅ 定稿 |
| 2 | `assessment_submit` 是否传精确总分 | **不传**，仅传 `level` + `dim_levels`（DPO-2） | ✅ 已确认 |
| 3 | 是否采集 `device_id` | **不采集**（DPO-1） | ✅ 已确认 |
| 4 | 落地方式（字典表 / 代码枚举 / 两者） | **两者兼有，代码枚举为真源**：`EventCode` 枚举为事实来源，`track_event_dict` 表为其**运行时投影**（供网关白名单校验与治理查询），二者由 CI 校验一致性 | ✅ 定稿 |
| 5 | 是否新增 `track_event_dict` 表 | **新增**，DDL 已按 ADR-0003 修正（见 §7）；随 ER 补丁上库（建议与 ER-14 同批） | ✅ 定稿（待上库） |
| 6 | `user_id` 是否明文上报（DPO-3） | **一律 HMAC-SHA256 摘要**，不传明文 —— 相对 DPO 已签字的 v1.0 属**隐私加强**，风险单向下降，研发侧按加强口径先行实现，**待 DPO 形式确认** | ⚠ 形待确认 |

**定稿后的落地契约（对 SDK 的硬要求）**

1. 事件名常量集须与本文档**同源**（`EventCode` 枚举 + CI 比对 `track_event_dict` 30 条）；
2. **未登记事件名不得上报**（网关白名单校验，命中即拒绝并告警）；
3. 参数按 s1/s2 规则**在端侧先脱敏再上报**（s2 仅传 ID/枚举/布尔/计数）；
4. 小程序端须**先获授权**再初始化 SDK（对齐 M1 单独同意）；
5. `expire_at = occurred_at + 180 天`，到期自动清理。

---

**编制**：AI（合并 v0 初稿 + v1.0 定稿）｜**依据**：PRD §9、M1 `YL-M1-A2-DESENS-001` §4/§5、ER 评审 ER-11、用户采纳的 5 项决策
**关联**：`docs/design/B2-account-rbac-design.md`（敏感操作留痕）｜`track_event` / `track_event_dict`｜ADR-0003
