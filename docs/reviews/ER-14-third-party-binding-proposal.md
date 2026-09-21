# ER-14 提案 · 第三方账号绑定表（`sys_user_third_party`）

> 编号 **YL-M2-ER14-BINDING**｜起草 2026-09-21｜状态：**✅ ER 三方面通过（2026-09-21）——建表已落地**
> 起草依据：B2 渠道适配器落地过程中暴露的 DDL 缺口（`rky1SW` / `rpW1xZ`）
> 关联：《ER 三方评审纪要》ER-01～ER-13｜ADR-0003（主键/时区/外键/软删除全库口径）｜ER-11 埋点字典（openId 不落库）
> 影响文件：`docker/mysql/init/02_schema.sql`、`scripts/verify-schema.sh`、`docs/design/B2-account-rbac-design.md` §6.2

---

## 一、问题

B2 落地微信/抖音渠道登录时发现：**平台身份无法持久绑定到本服务账号**。

| 检查项 | 结果 |
|---|---|
| `sys_user` 是否有 `openid` / `unionid` 列 | **无**（仅有 `username` / `phone_enc` / `phone_hash`） |
| 全库是否有第三方账号绑定表 | **无**（搜索 `openid` / `union_?id` / `third` / `binding` 均无命中） |
| 现本期如何解 | **以「手机号 HMAC 摘要」命中 `sys_user.phone_hash` 完成账号解析**（`PhoneHashBindingResolver`） |

**该绕行方案的代价**：用户在微信/抖音端**每次登录都必须授权手机号**——因为系统没有"记住这个平台账号是谁"的地方。
一旦用户拒绝授权手机号（平台允许），登录**直接失败**，无法回退。

> 因此本期实现是**可上线但体验有损**的最小方案：功能可用、不阻塞 M2；
> 若要支持「首次授权后免手机号登录」（行业惯例），**必须补表**。

---

## 二、提案：新增 `sys_user_third_party`

```sql
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
    `retain_until`  DATETIME     NULL COMMENT '物理删除时间 = 账号注销时间 + 30 天（PM 裁决 2026-09-21 §5.1-A）',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_platform_openid` (`platform`, `open_id_hash`, `active_uk`),
    UNIQUE KEY `uk_user_platform`   (`user_id`, `platform`, `active_uk`),
    KEY `idx_union_id` (`platform`, `union_id_hash`),
    KEY `idx_phone_hash` (`phone_hash`),
    KEY `idx_retain_until` (`retain_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='第三方账号绑定（ER-14）';
```

### 2.1 关键设计取舍

| # | 取舍 | 理由 |
|---|---|---|
| 1 | **只存 `open_id_hash`，不存 `open_id` 明文** | 延续 B2 已落地的承诺「openId 不落库、不入日志」（对齐 ER-11 S2 与 PIPL 最小必要）。换证后立即 HMAC，明文只在内存活于单次请求内。 |
| 2 | 复用 `phone_hash` 同算法（HMAC-SHA256 + 同一密钥） | 使「手机号绑定」与「平台绑定」两条路径**可交叉校验**，避免两套摘要口径漂移。 |
| 3 | 双唯一键均带 `active_uk` | ① 同平台同 openid 仅一条有效绑定（防重复绑定）；② 同一用户同平台仅一条有效绑定（换号可解绑后重绑）。软删后唯一键自动释放 —— 沿用全库 `active_uk` 方案（ER-13 / ADR-0003）。 |
| 4 | 应用层主键、`DATETIME`、**无外键** | 严格遵循 ADR-0003；引用完整性下沉应用层（`user_id` 存在性由 `AccountAggregateService` 保证）。 |
| 5 | `platform` 用 `VARCHAR(16)` 而非 `TINYINT` | 与 `login_log.login_type`（`WECHAT`/`DOUYIN`/`PWD`/`FACE`）取值风格一致，便于人工排障与报表直读。 |
| 6 | **新增 `retain_until`（PM 裁决 §5.1-A 追加）** | 账号注销**不立即删**，保留 30 天后物理删除：注销时置 `retain_until = 注销时间 + 30 天`，由定时清理任务按 `retain_until <= NOW()` 物理删除（含 `idx_retain_until` 支撑）。**与 ER-03 保留期机制同构**，不引入第二套口径。 |

### 2.2 合规口径（需 ER 裁决）

| 议题 | 建议口径 | 依据 |
|---|---|---|
| **留存期** | **随账号存续**；解绑 → `deleted=1`；**账号注销 → 保留 30 天后物理删除**（不适用 180 天埋点规则）⚠ **PM 裁决修正原「立即物理删除」** | ER-03 分档未覆盖此表；绑定关系属「实现合同目的所必需」，账号灭失即失必要性（PIPL 第 19/47 条）。30 天窗口用于客服申诉与误注销回滚 |
| **敏感分级** | `open_id_hash` / `union_id_hash` / `phone_hash` = **S1**（摘要值，不可逆）；**无 S2 字段** | 对齐 ER-11 字典三级口径 |
| **告知同意** | 首次绑定写入 `consent_record`（`subject_type=ELDER/FAMILY`，`policy_code=THIRD_PARTY_BIND`） | 承接 ER-08 告知同意留痕 |
| **撤回权** | 用户可在「账号设置」解绑；解绑后本渠道登录即失效，**不影响账密登录** | PIPL 第 15 条（撤回同意） |
| **老人端** | 老人**本人不注册**（PRD §2.1），其平台绑定挂在**家属账号**下，不新增老人侧绑定入口 | PRD §2.1「可不注册，家属代管」 |

---

## 三、影响面评估

| 维度 | 评估 |
|---|---|
| **数据模型** | `02_schema.sql` 表数 **36 → 37**（verify 脚本动态断言 37 → 38，含 `gb_rule_version`）；新增 1 张表，**不改动任何既有表**。 |
| **需求影响** | **不新增功能点**；使 `rpW1xZ`（多端登录适配）的验收标准「微信授权+手机号、抖音授权+手机号」具备**长期可用性**（否则每次强制手机号授权）。 |
| **工期影响** | 表 + Mapper + 适配器接入约 **1 人日**；可并入 B2 收尾或 C 模块开工前。 |
| **合规影响** | **正向**：① 以摘要替代明文，避免新增敏感信息明文面；② 补上「绑定关系」这一此前**无留痕载体**的处理活动，告知同意与撤回权可落地。 |
| **测试回归** | 断言 **+7 项**（含保留期列，绝对计数见合并口径表）；`verify-schema.sh` 需重建库后全量重跑，既有 43 项口径不变。 |
| **四端** | 不改变登录入口 `POST /auth/login/{channel}`；仅在适配器内部把「手机号解析」升级为「**先查绑定表，未命中再走手机号**」的降级链。 |

---

## 四、断言增补计划（`verify-schema.sh`，新增 6 项）

| # | 断言 | 期望 |
|---|---|---|
| 1 | 表存在 `sys_user_third_party` | 1 |
| 2 | **明文 `open_id` 列必须为 0** | 0 |
| 3 | `open_id_hash` 列存在且为 `CHAR(64)` | 1 |
| 4 | `active_uk` 生成列（全库 **3 → 4**）；`deleted` 列（19 → 20） | 4 / 20 |
| 5 | 无 `TIMESTAMP` 列 / 无外键约束（纳入 ADR-0003 全库断言，自动覆盖） | 0 / 0 |
| 6 | 行为验证：软删后同 `(platform, open_id_hash)` 可重绑；未删除时重复绑定被唯一键**真实拒绝** | 通过 |
| 7 | **保留期列 `retain_until`（PM 裁决 §5.1-A 追加，全库 4 → 5）**；`idx_retain_until` 索引存在 | 5 / 1 |

> ⚠ **断言绝对值需按「合并口径表」重算**：ER-14（+6→本表实为 +7）、ER-11 v1.1（`track_event_dict` 建表）、
> CR-M2-001（+8 红线）三条增量落地后，断言总量与各计数断言（`active_uk` / `deleted` / `retain_until` / 表数）
> **不能各自独立累加**，必须以「合并口径表」一次性定格。详见 `docs/quality/verify-schema-assertion-reconciliation.md`。

---

## 五、待 ER 裁决事项（附 PM 最终裁决）

| # | 裁决点 | 研发建议 | **PM 最终裁决（2026-09-21）** |
|---|---|---|---|
| 1 | 是否本期建表（还是并入 V2） | **本期建**：渠道登录是 P0 且已实现，缺表会使「免手机号登录」永久不可达 | ✅ **本期建**（同意） |
| 2 | `openid` 是否**允许留存明文**（便于排障/对账） | **不留明文**，仅 HMAC 摘要 —— 与既实现承诺一致 | ✅ **禁止存明文，仅存 HMAC 摘要**——明确否决明文方案，「便于排障/对账」不构成留存明文的正当理由 |
| 3 | 双唯一键口径是否接受（同用户同平台唯一 + 同平台同 openid 唯一） | 接受 | ✅ **同意双 `active_uk`**（仅存 HMAC 摘要，故唯一键建立在 `open_id_hash` 上） |
| 4 | 留存期：随账号存续 + 注销物理删除 | 接受（若要求「解绑后保留 N 年以备监管」，需改用 `retain_until` 模式） | ✅ **同意注销物理删除**，但**修正为「注销后保留 30 天再物理删除」**——见下方 §5.1 差异化说明 |
| 5 | 是否要求「换绑需二次验证」（账号被冒用风险） | **建议要求**：换绑/解绑纳入敏感操作（`need_second_verify=1`），与 `account:family:unbind` 同口径 | ✅ **必须要求，且按次数分档**：**首次换绑/解绑 = 短信验证；后续换绑/解绑 = 人脸验证** |

### 5.1 PM 裁决的 3 处与原建议的差异（研发必须按差异实现）

| 差异 | 原建议 | PM 最终裁决 | 实现影响 |
|---|---|---|---|
| **A. 注销后不立即删** | 账号注销 → **立即**物理删除 | 注销后 **保留 30 天**，期满物理删除 | ① 表需可定位「哪些绑定已随账号注销且已过 30 天」→ **新增 `retain_until DATETIME` 列**（与 ER-03 既有机制一致，`retain_until = 注销时间 + 30 天`），由定时清理任务按 `retain_until <= NOW()` 物理删除；<br>② 结构断言连带变化：`retain_until` 列 **4 表 → 5 表**（见 §四 断言增补 #7）；<br>③ 30 天窗口内登录原渠道应**拒绝**（账号已注销，不因保留期而复活） |
| **B. 换绑验证分档** | 统一纳入敏感操作（`need_second_verify=1`） | **首次短信 / 后续人脸** | `need_second_verify=1` 的第四闸**验证方式需按次数动态选择**，不能只留一个开关 → `SecondVerifyGuard` 需支持按 `(user_id, platform)` 查历史绑定次数决定通道 |
| **C. 明文禁令升级为硬否决** | 建议不留明文 | **禁止**（等同上位约束） | 断言「明文 `open_id` 列必须为 0」由「建议」升级为**发布阻断项** |

> ⚠ **C 项连带合规动作**：`open_id_hash` 一旦成为唯一检索键，**HMAC 密钥即成为账号可识别性的唯一凭据**，
> 密钥轮换必须支持双密钥并行窗口（与 `SensitiveFieldCodec` 既有双密钥装配一致），否则轮换即导致全部平台绑定失效。

---

---

## 六、生效条件与落地清单

**生效条件**：✅ **已满足** —— ER 三方面（产品 / 研发 / 合规）于 2026-09-21 就 §五 五项裁决达成一致；建表已并入 `02_schema.sql`，ER-14 断言 6 项由守卫转为真实断言。

| # | 动作 | 产出 |
|---|---|---|
| 1 | 应用 DDL 补丁 | `02_schema.sql` 追加建表（含 `retain_until`；补丁见 `ER-14-DDL-Patch-Proposal.sql`） |
| 2 | 补断言 | `verify-schema.sh` +7 项（表存在 / 明文 0 / `open_id_hash` 类型 / `active_uk` / ADR-0003 覆盖 / 行为验证 / `retain_until`）；**绝对计数以合并口径表为准** |
| 3 | 代码 | `SysUserThirdParty` 实体 + Mapper；`ThirdPartyBindingResolver` 增「按摘要查绑定表」实现 |
| 4 | 适配器降级链 | `rpW1xZ`：**先查绑定 → 未命中再取手机号 → 仍无则报未绑定**（不伪装成「用户未绑定」） |
| 5 | 文档同步 | 设计 §6.2 缺口关闭；README 表数 +1；ER 纪要 Action Item 增 ER-14 行 |
| 6 | **二次验证分档（PM 裁决 §5.1-B）** | `03_seed_rbac.sql` 增敏感点「第三方绑定换绑/解绑」；`SecondVerifyGuard` 支持**按次数选通道**：首次短信 / 后续人脸 |
| 7 | **保留期清理任务（PM 裁决 §5.1-A）** | 新增定时任务：注销时置 `retain_until = 注销 + 30 天`；按 `retain_until <= NOW()` 物理删除（需 XXL-JOB；与本仓当前「无定时任务」现状一并立项） |
| 8 | **密钥轮换窗口（PM 裁决 §5.1-C 连带）** | `SensitiveFieldCodec` 双密钥并行校验覆盖 `open_id_hash`；轮换方案须文档化（否则绑定全失效） |

**回滚**：纯新增表，回滚为 `DROP TABLE` + 撤销 6 项断言；**无既有数据依赖**。

---

## 七、闭环记录

| 日期 | 事项 | 结果 | 确认人 |
|---|---|---|---|
| 2026-09-21 | 渠道适配器发现绑定缺口 | ✅ 已登记（设计 §6.2，未擅自加表） | 研发侧 |
| 2026-09-21 | 起草 ER-14 提案 + DDL 补丁 | ✅ 完成，待裁决 | 研发侧 |
| 2026-09-21 | **PM 裁决 5 项已填（§五）** | ✅ 完成。3 处修正：① 注销后**保留 30 天**再物理删除（原为立即删）；② 换绑验证**分档**（首次短信 / 后续人脸）；③ 明文禁令**升级为硬否决** | 产品经理 |
| 2026-09-21 | **ER 三方面附议通过 + 建表落地** | ✅ `02_schema.sql` 增 `sys_user_third_party`（含 `retain_until` + `idx_retain_until`）；ER-14 断言 6 项由守卫转为真实断言，`verify-schema` 60 项全绿 | ER / 研发侧 |
| — | 适配器接入（按摘要查绑定表）+ retain_until 清理任务（XXL-JOB） | ⏳ 待办（随 C/D 模块与本仓定时任务立项） | 研发 |
