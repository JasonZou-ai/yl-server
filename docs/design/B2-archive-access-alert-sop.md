# 银龄守护 · 档案异常访问告警响应 SOP（ARCHIVE_ACCESS_ALERT）

> 编号 **YL-M2-ALERT-SOP-v1.0**｜编制 2026-09-22｜适用范围：生产/预发环境 `elder:archive:read` 行为异常告警
> 配套：CR-M2-001 §2.5（PM 补充意见）· `ArchiveAccessRateGuard` · `ArchiveAccessGuardMode`
> 状态：**✅ 已落地（守卫 + mode 配置项）**，值班与责任人待 PM 指派后填入 §7

---

## 1 TL;DR

| 项 | 口径 |
|---|---|
| 告警事件 | `audit_log.action = ARCHIVE_ACCESS_ALERT`，`biz_type = ELDER`，`success = 0` |
| 触发条件 | 同一账号滑动 **1 小时**内调用档案类接口（`elder:archive:read`）**> 50** 次 |
| 监控对象 | 非护理角色：**ELDER / FAMILY / SUPERVISOR**（ASSESSOR / ORG_ADMIN 默认不纳入） |
| 处置模式 | `mode`：`off` / `audit`（灰度）/ **`alert`（默认）** / `enforce`（须先改契约） |
| 去重 | 同一账号同一窗口内只告警一次（Redis `tryAcquireOnce`） |
| 失败姿态 | **失败开放**：计数/去重/通知异常只记警告，绝不阻断档案读取 |
| 响应时限 | 确认 **T+30min**｜处置闭环 **T+4h**｜超时升级至项目负责人 |

**一句话**：本告警是**旁路监控能力**，不是拦截能力。它的价值在于让「非护理角色批量拉档」这件事**留痕且有人看**，而不是把它挡住。

---

## 2 告警分级

以 `count / threshold` 比值与账号角色判定严重度，决定响应时限：

| 级别 | 判定条件 | 含义 | 响应时限 | 闭环时限 |
|---|---|---|---:|---:|
| **S1** | 比值 **> 5**（即 1h 内 > 250 次）**或** 账号属 SUPERVISOR | 疑似批量爬取/账号失陷 | **T+15min** | **T+2h** |
| **S2** | 比值 **2 ~ 5**（1h 内 100 ~ 250 次） | 明显异常，需人工核实 | T+30min | T+4h |
| **S3** | 比值 **1 ~ 2**（1h 内 51 ~ 100 次） | 疑似误报（家属多老人照护等合理场景） | T+2h | **T+1 工作日** |

> **SUPERVISOR 一票升 S1**：监管角色本身具备 `data_scope=4`（只读全局），其异常访问的潜在影响面远大于普通家属账号，故不论次数直接按 S1 处理。

---

## 3 响应流程（处置动作清单）

| 步骤 | 动作 | 负责 | 时限 | 留痕 |
|---|---|---|---|---|
| 1 | **检知**：告警写入 `audit_log` + 通知监管角色（`notifier`，默认结构化日志） | 系统 | 实时 | 自动 |
| 2 | **确认**：值班人认领告警，标记「已确认」 | 监管值班 | S1 15min / S2 30min / S3 2h | 工单 |
| 3 | **核实**：拉该账号窗口内访问明细，判断是否合理（多老人照护、批量核对等） | 监管值班 | 确认后 1h 内 | 工单 |
| 4 | **处置**（三选一）<br>① 判定误报 → 关闭并登记基线<br>② 判定异常 → 临时收紧（见 §4）<br>③ 判定账号失陷 → 冻结账号 + 通知机构管理员 | 监管值班 | S1 2h / S2 4h / S3 1 工作日 | 工单 + `audit_log` |
| 5 | **升级**：超闭环时限未处理 → 升级机构管理员 → 项目负责人 | 监管值班 | 超时即升 | 工单 |
| 6 | **复盘**：每周汇总告警数/误报率，回看 `threshold` 是否需要调整 | PM + 研发 | 每周 | 周报 |

**误报处理基线**（登记后同类告警可快速关闭，不计入误报率）：
- 家属账号绑定 ≥3 位老人，且访问集中在绑定对象范围内；
- 机构侧批量核对（需提前报备，报备窗口内不计告警）；
- 压测/联调账号（应加入白名单，见 §4 `monitored-roles` 调整）。

---

## 4 临时收紧手段（处置动作 ②的三档工具）

按影响面从小到大，**不要一上来就动 `mode`**：

| 手段 | 改法 | 影响面 | 适用 |
|---|---|---|---|
| 加白名单 | 把账号角色移出 `monitored-roles` | 仅该角色 | 压测/联调账号 |
| 降阈值 | 调小 `threshold`（如 50 → 20） | 全局 | 敏感时期（如监管检查周） |
| 开拦截 | `mode: enforce` | 全局且**会拒绝请求** | ⚠ **须先完成契约变更**，否则客户端收到未约定的 429 |

```bash
# 热改配置（无需发版，改后重启或配合配置中心刷新）
ARCHIVE_GUARD_MODE=alert        # off | audit | alert | enforce
ARCHIVE_GUARD_THRESHOLD=50
ARCHIVE_GUARD_WINDOW_MINUTES=60
ARCHIVE_GUARD_MONITORED_ROLES=ELDER,FAMILY,SUPERVISOR
```

> ⚠ **`enforce` 的前置条件**：`elder:archive:read` 是非敏感权限点，契约未声明「超频即拒绝」。开启后客户端会收到
> `ErrorCode.RATE_LIMITED(10429)`，属**契约破坏性变更**，须随 C/D 模块一并补充错误码与重试语义后方可启用。
> 未变更契约前只允许在故障窗口内**临时**启用，且须同步通知前端。

---

## 5 灰度路径（建议，本迭代执行）

| 阶段 | `mode` | 时长 | 观察指标 | 通过标准 |
|---|---|---|---|---|
| G1 灰度 | `audit` | 7 天 | 告警量、误报率、账号分布 | 日均告警 ≤ 5 且误报率 ≤ 30% |
| G2 正式 | `alert` | 长期 | SLA 达成率、闭环时长 | 确认率 ≥ 95%、闭环率 ≥ 90% |
| G3 拦截 | `enforce` | 待定 | — | **阻塞于契约变更**（C/D 模块） |

G1 阶段只留痕不通知，用于**攒出真实基线**再定 `threshold`，避免一上线就用 50 这个拍脑袋值打扰监管侧。

---

## 6 告警查询与自检

```sql
-- 近 24 小时告警明细（按次数倒序）
SELECT id, user_id, detail, created_at
FROM audit_log
WHERE action = 'ARCHIVE_ACCESS_ALERT'
  AND created_at >= NOW() - INTERVAL 24 HOUR
ORDER BY created_at DESC;

-- 告警量趋势（按天，用于 G1 基线评估）
SELECT DATE(created_at) AS d, COUNT(*) AS alerts
FROM audit_log
WHERE action = 'ARCHIVE_ACCESS_ALERT'
GROUP BY DATE(created_at) ORDER BY d DESC LIMIT 14;
```

**守卫自检**（确认守卫真的在跑，而不是配错静默关闭）：
```bash
grep -i "档案异常访问守卫" <应用日志>   # 启动时应有 mode=enforce 警示（仅 enforce）或配置不完整告警
redis-cli --scan --pattern 'yl:arch:count:*'   # 有监控角色访问时应能看到计数键
```

---

## 7 值班与责任人

| 角色 | 职责 | 人选 |
|---|---|---|
| 监管值班 | 告警确认、核实、处置 | ⏳ **待 PM 指派** |
| 机构管理员 | 接收冻结/收紧通知，执行账号处置 | 各机构 ORG_ADMIN |
| 安全负责人 | S1 告警升级后的决策 | ⏳ **待 PM 指派** |
| 研发值班 | 守卫可用性、`mode`/`threshold` 调整执行 | ⏳ **待 PM 指派** |

> 本节人选由 PM 指派后回填；**未回填前 SLA 时限不生效**（无法追责），§2 时限自回填日起算。

---

## 8 与其他机制的关系

| 机制 | 层 | 作用 | 边界 |
|---|---|---|---|
| 第一闸 `JwtAuthFilter` | 身份 | 认证 | 不管权限 |
| 第二闸 `PermissionAspect` | 权限点 | 有无该权限 | 不判频次 |
| 第三闸 `DataPermissionInterceptor` | 数据域 | `data_scope` 可见范围 | 不判总量 |
| 第四闸 `SecondVerifyGuard` | 敏感操作 | 二次验证 | 只覆盖 4 个敏感权限点 |
| **本守卫** | **行为** | **频次异常告警** | **只告警不拦截（默认）** |

**为什么本告警不进埋点域（ER-11）**：`ARCHIVE_ACCESS_ALERT` 是安全审计事件，写入 `audit_log`，
不进 `track_event_dict`（30 个业务事件的域）。埋点域面向产品分析，审计域面向安全合规，两者不得混用——
混用会让埋点保留期（180 天）反过来约束审计留痕的留存要求。

---

## 9 变更记录

| 日期 | 动作 | 结果 | 责任人 |
|---|---|---|---|
| 2026-09-22 | 守卫落地（`ArchiveAccessRateGuard`，只告警不拦截） | ✅ 已合入 main | 研发 |
| 2026-09-22 | 新增 `mode` 配置项（off/audit/alert/enforce，默认 alert） | ✅ 12 例单测全绿 | 研发 |
| 2026-09-22 | 本 SOP v1.0（分级 + SLA + 处置 + 灰度路径） | ✅ 已发布 | 研发 |
| — | §7 值班人选回填 | ⏳ 待 PM 指派 | PM |
| — | G3 拦截模式启用 | ⏳ 阻塞于契约变更（C/D 模块） | 研发 + PM |
