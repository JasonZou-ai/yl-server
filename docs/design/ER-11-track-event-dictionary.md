# 四端统一埋点事件名字典（初稿 v0）

> 编号 **YL-M2-ER11-TRACK-DICT-v0**｜编制 2026-09-21｜状态：**初稿，待评审定稿**
> 真源：PRD §9 数据埋点需求 ＋ M1《脱敏与埋点合规方案》`YL-M1-A2-DESENS-001` §4（8 事件禁采清单）
> 合规基线：《个人信息保护法》(PIPL)、《数据安全法》、GB/T 42195-2022、四端平台规则
> 关联：ER 评审 ER-11（埋点事件名字典）｜落库表 `track_event`（保留 ≤180 天，`expire_at`）
> 覆盖四端：**WXMP**（微信小程序）/ **DYMP**（抖音小程序）/ **IOS** / **ANDROID**（＋管理后台 WEB）

---

## 0 命名与分级约定

**事件名**：`<stage>_<object>_<action>`，全小写下划线，≤ 64 字符（与 `track_event.event_name VARCHAR(64)` 一致）；
同一业务动作跨四端**必须同名**（四端差异仅体现在公共属性 `app_channel`）。

**敏感分级（埋点维度）**：

| 级别 | 含义 | 处置 |
|---|---|---|
| **S0** | 不含个人信息 | 直接上报 |
| **S1** | 含可识别信息 | **哈希/泛化后**上报（`*_hash`、分段、枚举） |
| **S2** | 涉敏感个人信息 | **禁采原文**，仅传「ID / 枚举 / 布尔 / 计数」——对齐 M1 禁采清单 |

**通用红线（硬性，8 事件禁采清单的推广）**：
1. **禁采**：身份证全文/图像、人脸原始图像、健康明细原文、报告正文、题干原答文本、缓存内容、页面内敏感字段值；
2. 老人端埋点须**本人或家属授权**，未授权不采集；
3. 埋点**脱敏后入库**，`expire_at = occurred_at + 180 天`，到期自动清理；
4. 事件名与参数**纳入白名单校验**，未登记事件名不得上报（防"随手加字段"）。

---

## 1 公共属性（所有事件携带）

| 属性 | 类型 | 说明 | 敏感 |
|---|---|---|---|
| `app_channel` | enum | `WXMP`/`DYMP`/`IOS`/`ANDROID`/`WEB` | S0 |
| `app_version` | string | 客户端版本号 | S0 |
| `os` | string | 操作系统及版本 | S0 |
| `device_id` | string | **匿名设备标识**（随机 UUID，不可反解，卸载即变） | S1 |
| `session_id` | string | 会话 ID（随机） | S0 |
| `user_role` | enum | `ELDER`/`ASSESSOR`/`FAMILY`/`ORG_ADMIN`/`SUPERVISOR` | S0 |
| `user_id_hash` | string | 用户 ID 的 HMAC-SHA256（**不传明文 user_id**） | S1 |
| `org_id` | long | 机构 ID（业务主体，非个人信息） | S0 |
| `page_id` | string | 页面标识（白名单） | S0 |
| `ts_client` | long | 客户端时间戳（ms） | S0 |
| `network` | enum | `WIFI`/`4G`/`5G`/`NONE` | S0 |
| `trace_id` | string | 链路追踪 ID | S0 |

---

## 2 阶段一 · 登录（`auth_*`）

| 事件名 | 中文名 | 触发时机 | 关键参数 | 敏感 | 四端 |
|---|---|---|---|:--:|---|
| `auth_page_view` | 登录页曝光 | 进入登录页 | `channel_list[]` | S0 | 全 |
| `auth_login_start` | 发起登录 | 点击登录 | `login_channel`(WECHAT/DOUYIN/PWD/FACE)、`device_type` | S0 | 全 |
| `auth_login_result` | 登录结果 | 登录返回 | `login_channel`、`success`、`fail_code`(枚举)、`is_new_user` | **S1** | 全 |
| `auth_token_refresh` | 令牌刷新 | access 过期换发 | `success` | S0 | 全 |
| `auth_logout` | 退出登录 | 主动/超时登出 | `reason`(USER/TIMEOUT) | S0 | 全 |

> 🚫 **禁采**：手机号、短信验证码、口令、`openid`/`unionid` 原文 → 如需关联用哈希。

## 3 阶段二 · 建档（`elder_*`）

| 事件名 | 中文名 | 触发时机 | 关键参数 | 敏感 | 四端 |
|---|---|---|---|:--:|---|
| `elder_create_start` | 开始建档 | 点击新建 | `entry`(SCAN/MANUAL) | S0 | App |
| `elder_idcard_ocr` | 身份证识别 | 调用 OCR | `success`、`ocr_ms` | **S2** | 全(App) |
| `elder_create_submit` | 提交建档 | 提交表单 | `has_idcard`、`has_phone`（**仅布尔**） | **S1** | App |
| `elder_create_result` | 建档结果 | 建档返回 | `success`、`elder_id_hash` | **S1** | App |
| `elder_info_edit` | 编辑档案 | 保存修改 | `field_count`、`edit_type`(枚举) | **S1** | 全 |
| `elder_search` | 检索老人 | 搜索提交 | `search_type`(PHONE_EXACT/NAME_EXACT)、`result_count` | **S2** | 全 |

> 🚫 **禁采**：身份证全文与图像、姓名、搜索关键字；手机号检索**仅接受 11 位精确匹配**（对齐 M1 §2 脱敏实现口径），不开放模糊查询。
> ✅ **脱敏**：`elder_id_hash`＝老人 ID 的 HMAC-SHA256；姓名一律不回传。

## 4 阶段三 · 评估填报（`assessment_*`）

| 事件名 | 中文名 | 触发时机 | 关键参数 | 敏感 | 四端 | M1 禁采清单 |
|---|---|---|---|:--:|---|:--:|
| `assessment_create` | 新建评估单 | 创建评估单 | `elder_id`、`scene`、`assessor_id`、`org_id` | **S2** | 全 | ★ #1 |
| `assessment_questionnaire_start` | 开始作答 | 进入问卷 | `order_id_hash`、`dimension_count`、`mode`(ONLINE/OFFLINE) | **S1** | 全 | — |
| `assessment_item_answer` | 单项作答 | 每项作答 | `item_id`、`dimension`、`score_band`(档位)、`elapsed_ms`、`has_evidence` | **S2** | 全 | ★ #2 |
| `assessment_item_skip` | 跳过/拒答 | 选择不适用 | `item_id`、`answer_state`(SKIP/REFUSE) | **S1** | 全 | — |
| `assessment_draft_save` | 暂存草稿 | 点击暂存 | `answered_count`、`elapsed_ms` | S0 | 全 | — |
| `assessment_submit` | 提交评估 | 提交评估单 | `order_id`、`total_score`、`grade_code`、`elapsed_ms` | **S2** | 全 | ★ #3 |
| `assessment_offline_enter` | 进入离线 | 断网切离线 | `cached_count` | S0 | App | — |
| `assessment_evidence_capture` | 留证采集 | 拍照/录音/签名 | `item_id`、`evidence_type`(PHOTO/AUDIO/SIGN)、`is_required` | **S2** | App | — |

> 🚫 **禁采**：老人姓名/身份证、题干原答文本、健康明细、逐题明细原始文本。
> ✅ **脱敏**：总分传**精确值**（评估结论必需且为业务主体数据），但**逐题只传分值档位**；留证影像**不入埋点**，仅传类型与指标 ID。

## 5 阶段四 · 自动分级（`grade_*`）

| 事件名 | 中文名 | 触发时机 | 关键参数 | 敏感 | 四端 |
|---|---|---|---|:--:|---|
| `grade_auto_trigger` | 触发自动分级 | 提交后启动判定 | `order_id_hash`、`rule_version` | **S1** | 全 |
| `grade_auto_result` | 分级完成 | 判定返回 | `grade_code`、`score_band`(枚举分段)、`upgraded`、`elapsed_ms` | **S1** | 全 |
| `grade_upgrade_reason` | 上调原因 | 触发等级上调 | `reason_category`(CATEGORY 枚举) | **S2** | 全 |
| `grade_manual_review` | 人工复核分级 | 复核修改结果 | `changed`、`from_band`、`to_band` | **S1** | Web |

> ✅ **脱敏**：分数一律用 **`score_band` 分段**（完好/轻度/中度/重度/完全）；上调原因只传**大类枚举**（痴呆 / 精神行为障碍 / 照护风险事件），**不传 ICD 编码或诊断原文**（对齐 GB/T 42195-2022 上调规则口径）。

## 6 阶段五 · 报告（`report_*`）

| 事件名 | 中文名 | 触发时机 | 关键参数 | 敏感 | 四端 | M1 禁采清单 |
|---|---|---|---|:--:|---|:--:|
| `report_generate` | 生成报告 | 生成动作 | `order_id_hash`、`format`、`elapsed_ms` | **S1** | 全 | — |
| `report_view` | 查看报告 | 打开报告 | `report_id`、`viewer_role`、`source` | **S2** | 全 | ★ #4 |
| `report_publish` | 发布报告 | 复核通过发布 | `order_id_hash`、`reviewer_role` | **S1** | Web | — |
| `report_share` | 分享报告 | 分享 | `share_channel`、`target_role` | **S1** | 全 | — |
| `report_export` | 导出报告 | 导出 | `report_id`、`format`、`exporter_role`、`desensitize_level` | **S2** | Web/App | ★ #5 |
| `report_print` | 打印报告 | 打印 | `order_id_hash`、`page_count` | **S1** | Web/App | — |

> 🚫 **禁采**：报告正文、健康结论。
> ⚠️ `report_export` **必须二次验证 + `audit_log` 留痕**（对齐 B2 敏感操作，`data:export`）。

## 7 阶段六 · 照护计划（`care_*`）

| 事件名 | 中文名 | 触发时机 | 关键参数 | 敏感 | 四端 |
|---|---|---|---|:--:|---|
| `care_plan_generate` | 生成照护计划 | 生成 | `order_id_hash`、`item_count` | **S1** | 全 |
| `care_plan_edit` | 编辑照护计划 | 保存 | `added_count`、`removed_count` | S0 | 全 |
| `care_plan_confirm` | 确认照护计划 | 确认 | `order_id_hash`、`confirmer_role` | **S1** | 全 |
| `care_task_dispatch` | 派发照护任务 | 派发 | `task_count`、`assignee_role` | S0 | App/Web |
| `care_task_checkin` | 任务打卡 | 打卡 | `task_type`、`has_geo`(bool) | **S1** | App |
| `care_task_complete` | 任务完成 | 完成 | `task_type`、`duration_ms` | S0 | App |

> ✅ **脱敏**：定位**仅传城市级**（「留证/打卡」场景触发），**不传精确坐标**（对齐 M1 §1 字段分级）。

## 8 阶段七 · 档案留存（`archive_*`）

| 事件名 | 中文名 | 触发时机 | 关键参数 | 敏感 | 四端 |
|---|---|---|---|:--:|---|
| `archive_elder_view` | 查看档案 | 打开档案 | `elder_id_hash`、`viewer_role` | **S1** | 全 |
| `archive_plaintext_view` | 查看明文敏感字段 | 点击"查看原文" | `field_type`(IDCARD/PHONE/HEALTH)、`verify_method`(SCAN/PHONE/FACE) | **S2** | 全 |
| `archive_retention_set` | 设置保留期 | 归档配置 | `data_type`、`retain_years`(枚举) | S0 | Web |
| `archive_archive_trigger` | 触发归档 | 超保留期归档 | `data_type`、`count` | S0 | Web |
| `archive_download` | 档案下载 | 下载 | `archive_id`、`format`、`desensitize_level` | **S2** | Web |
| `archive_access_log_query` | 调阅访问记录 | 查询留痕 | `target_id_hash`、`result_count` | **S1** | Web |

> 🚫 **禁采**：字段明文的**值**（只传字段类型与验证方式）。
> ⚠️ `archive_plaintext_view` / `archive_download` **必须二次验证 + 留痕**。

## 9 通用与授权（`common` / `bind` / `offline` / `page`）

| 事件名 | 中文名 | 触发时机 | 关键参数 | 敏感 | 四端 | M1 禁采清单 |
|---|---|---|---|:--:|---|:--:|
| `app_launch` | 应用启动 | 冷/热启动 | `is_first_launch`、`cold_start_ms` | S0 | 全 | — |
| `page_stay` | 页面停留 | 离开页面 | `page_id`、`duration_ms`、`enter_at`、`leave_at` | **S2** | 全 | ★ #8 |
| `bind_relative` | 亲情绑定 | 提交绑定 | `relation`、`verify_method`、`success` | **S2** | 全(微信优先) | ★ #6 |
| `consent_record` | 同意留痕 | 同意/撤回政策 | `policy_code`、`policy_version`、`action`(GRANT/REVOKE)、`is_optional` | **S1** | 全 | — |
| `offline_sync` | 离线同步 | 联网同步 | `sync_count`、`conflict_count`、`elapsed_ms` | **S2** | App | ★ #7 |
| `error_occur` | 异常发生 | 捕获异常 | `error_code`、`page_id`、`is_network` | **S1** | 全 | — |
| `api_perf` | 接口性能 | 请求完成 | `api_path`(白名单)、`http_status`、`elapsed_ms` | S0 | 全 | — |

> 🚫 **禁采**：人脸原图、关系证明影像、页面内敏感字段值、离线缓存内容、异常堆栈中的敏感值。

---

## 10 与 M1 禁采清单（8 事件）对照 —— 可回溯

| M1 序 | M1 事件名 | 本字典归位 | 一致性 |
|:--:|---|---|:--:|
| 1 | `assessment_create` | §4 评估填报 | ✅ 同名 |
| 2 | `assessment_item_answer` | §4 评估填报 | ✅ 同名 |
| 3 | `assessment_submit` | §4 评估填报 | ✅ 同名 |
| 4 | `report_view` | §6 报告 | ✅ 同名 |
| 5 | `report_export` | §6 报告 | ✅ 同名 |
| 6 | `bind_relative` | §9 通用与授权 | ✅ 同名 |
| 7 | `offline_sync` | §9 通用与授权 | ✅ 同名 |
| 8 | `page_stay` | §9 通用与授权 | ✅ 同名 |

> **结论**：M1 8 个禁采事件**全部保留原名**，本字典在其基础上扩展到 **48 个事件**（8 个阶段 + 公共属性），
> 不做重命名、不简并（对齐「不得自造/简并」的承接口径）。

## 11 四端差异化

| 差异点 | 说明 |
|---|---|
| 事件名 | **四端统一**，无端后缀；差异由公共属性 `app_channel` 表达 |
| 渠道专属事件 | `auth_login_start.login_channel` 取值不同：微信 `WECHAT`、抖音 `DOUYIN`、iOS `FACE/PWD`、Android `PWD` |
| 端专属事件 | `assessment_offline_enter` / `assessment_evidence_capture` / `offline_sync` 仅 **App（iOS/Android）**；`report_publish` / `archive_*` 仅 **Web 后台** |
| 小程序限制 | 微信/抖音小程序**不支持**长时后台定位与本地大文件缓存 → 相关事件（`care_task_checkin.has_geo`）在小程序端恒为 `false` |
| 采集时机 | 小程序端埋点须**先获授权**再初始化 SDK（对齐 M1 §6 单独同意） |

## 12 留存与清理

- 全部埋点事件 `expire_at = occurred_at + 180 天`（对齐 PRD §9 与 M1 §5）；由 XXL-JOB 每日清理。
- 埋点**不参与** ER-03 的评估/报告 5 年保留（埋点属独立短周期数据）。
- 事件名与参数白名单变更须走**变更登记**（`track_event_dict` 或代码常量），禁止运行时临时加字段。

## 13 落地方式（待评审选择）

| 方案 | 说明 | 优点 | 代价 |
|---|---|---|---|
| **A. 字典表** | 新增 `track_event_dict(event_name, stage, cn_name, sensitivity, retain_days, status)` 播种本字典 | 可 CI 断言、可运营维护、可版本化 | 表数 37 → 38 |
| **B. 代码常量** | 各端 SDK 维护 `EventName` 枚举 + 参数白名单 | 无 DB 变更、编译期校验 | 无法门禁化、难运营 |
| **C. A+B** | 表为真源 + 代码由表生成枚举 | 兼顾治理与校验 | 需生成器 |

> 建议 **C**（与 `gb_rule` / RBAC 的「种子落库 + 门禁断言」既有范式一致）；本初稿评审定稿后再落。
> 落地与 `r5HcnX`（B3-5 跨端统一基础库）的**埋点 SDK** 一并实施。

## 14 待确认项（评审焦点）

| # | 待确认 | 说明 |
|---|---|---|
| 1 | 事件**数量**是否收敛 | 本稿 48 个；若认为偏多，可只保留 8 阶段关键节点（可裁至 ~30） |
| 2 | `assessment_submit` 是否可传 `total_score` 精确值 | 本稿判定为「业务主体数据、可精确」；如 DPO 认为须分段，改为 `score_band` |
| 3 | 是否采集 `device_id` | 本稿列为 S1（匿名化），部分平台要求不得采集设备标识，需按四端规则确认 |
| 4 | 落地方式选 A / B / C | 见 §13 |
| 5 | 是否新增 `track_event_dict` 表 | 若选 A/C，表数 37 → 38，需同步门禁断言 |

---

**编制**：研发（虚拟-后端开发）｜**依据**：PRD §9、M1 `YL-M1-A2-DESENS-001` §4/§5、ER 评审 ER-11
**关联**：`docs/design/B2-account-rbac-design.md`（敏感操作留痕）｜`track_event`（`02_schema.sql` 第 678 行）
