# M2 待办登记册 · P1 / P2 项（登记不展开）

| 项 | 内容 |
|---|---|
| 登记日期 | 2026-09-23 |
| 登记人 | 产品负责人（P0 收口清单中的 P1/P2 项） |
| 用途 | 把 P1/P2 项**固化为可检索的卡片**，避免"开完会就淹没"；每张卡片自带落点、验收与依赖，可直接排期 |
| 编号规则 | 本文件不占用 CR 编号（未构成需求变更）；若某项实施中触及需求边界，再单独立 CR |
| 相邻卡片 | `CR-M2-002-authz-enforcement-gap.md`（P0-3 鉴权补位，**已登记**） |

---

## 卡 P1-4 · 容器中文字体 + ARCHIVED 超保留期归档（合并为一个调度链路）

| 项 | 内容 |
|---|---|
| 编号 | **P1-4** |
| 类别 | 部署可用性 + 合规（超期留存） |
| 紧急度 | 高 —— "**现在不做，线上会出事**"（PM 原话） |
| 依据 | ADR-0006 §约束 1（镜像需内置字体）、`docs/design/C4-report-pdf-care-review.md` §72（ARCHIVED 待并入 ADR-0004） |
| 现 状 | ① `Dockerfile` 基镜像 `eclipse-temurin:17-jre-jammy` **未安装任何字体**，`yl.report.pdf.font-path` 默认空 → 容器内 PDF 中文**降级渲染**（`chineseEmbedded=false`）；② `ARCHIVED` 仅是枚举/注释里的状态，**无任何实现** |
| 实施状态 | ✅ **两项均已实施**（2026-09-23，见文末「实施记录」） |
| 为什么必须合并 | PM 明确："正好和 ER-14 的 `retain_until` 清理任务合并成一个定时清理链路，**一次做两件事**"。两条都是"按保留期扫描 → 处理过期数据"，拆成两个 `@Scheduled` 是重复建设 |

### P1-4a 容器中文字体

| 项 | 口径 |
|---|---|
| 动作 | 镜像内置 CJK 字体（**须 OFL 等可商用许可**，如 Noto Sans CJK SC）+ `yl.report.pdf.font-path` 指向 + 启动自检 |
| 配置 | 复用已有环境变量入口 `REPORT_PDF_FONT`（`application.yml` L112：`font-path: ${REPORT_PDF_FONT:}`）——**无需改代码即可生效** |
| 自检 | 启动时校验 `font-path` 指向的文件存在且可解析；缺失时 **WARN 日志**（延续 ADR-0006 "不阻断业务"的降级口径），并在健康检查/启动横幅中显式暴露 |
| 验收 | 容器内 `POST /assessments/{id}/report/pdf` → `chineseEmbedded=true`、PDF 可下载且中文可见（当前该断言**只在 Windows 上通过**，容器内必挂） |
| 回归锚点 | `scripts/smoke-report-review-chain.sh` [3/6] 已含 `chineseEmbedded=true` 断言（2026-09-23 实测 `true` / 28789 bytes）——**换到容器跑即成为本项验收** |

### P1-4b ARCHIVED 超保留期归档（并入 ADR-0004 调度）

| 项 | 口径 |
|---|---|
| 动作 | 评估单/报告超过保留期后转 `ARCHIVED`（只读封存），与 ER-14 的三方绑定物理删除**共用一个扫描链路** |
| 前置 DDL | ~~需补归档标记（如 `archived_at`）与索引~~ → **实施时核查推翻**：`eval_order` 与 `eval_report` **已有** `retain_until` 列与 `idx_retain` 索引（ER-03 落地时即建），归档动作**不需要任何 DDL 变更**，故本项**不走** `*-Patch-Proposal.sql` 流程 |
| 调度归属 | 复用 ADR-0004 的 **限批（`batch-size`）+ 最大轮次（`max-rounds`）+ 幂等 + 失败不抛** 四条硬约束；业务动作放普通方法，注解只做触发壳 |
| ⚠️ 迁移红线 | ADR-0004「何时重新评估」写明：**任务数 ≥ 3** 才启动向 XXL-Job 的迁移评估。本项落地后调度任务数 = **2**（ER-14 清理 + 归档），**仍在 `@Scheduled` 区间内，不得顺带引入 Quartz/XXL-Job** |
| 验收 | ① 构造超保留期数据 → 跑一次任务 → 状态转 `ARCHIVED` 且原数据保留（非物理删除）；② ER-14 到期绑定行被物理删除；③ 两条动作在同一次调度内完成，日志含实际处理行数；④ 重复跑幂等（第二次 0 行、不误删） |

### 实施记录（2026-09-23）

| 项 | 结论 |
|---|---|
| P1-4a 字体 | ✅ `Dockerfile` 装 `fonts-noto-cjk` 并置 `REPORT_PDF_FONT`；候选口径收敛为 `PdfFontLocator`（唯一真源）；`GET /api/v1/system/health` 新增 `reportFont` 自检字段。本机实测 `{"status":"ready","path":"C:\\Windows\\Fonts\\simhei.ttf"}` |
| P1-4b 归档 | ✅ 新建端口 `domain/archive/ExpiredOrderArchiver` + 任务 `EvaluationArchiveTask` + Mapper 手写 SQL；**触发壳改为 `yl-bootstrap/…/RetentionChainScheduler`（全仓唯一 `@Scheduled`）**，ER-14 任务的注解随之上移 |
| 写入口 | ✅ `publish` / `markPublished` 写 `retain_until = 发布时点 + 5 年`（`RetentionPolicy` 单一口径）。实测发布后 `2031-09-23`；作废单为 NULL（正确：不进留存周期） |
| DDL | ✅ **无需变更**（`retain_until` + `idx_retain` 早已存在） |
| 验收① | ✅ `PUBLISHED + 过期 → ARCHIVED`（`row_version` 0→1）；数据仍在（非物理删除） |
| 验收② | ✅ 三方绑定过期行被物理删除（日志"删除 1 条"），未到期行保留 |
| 验收③ | ✅ 一次调度内串行完成，日志：`链路完成：三方绑定物理删除 1 条、超期评估单归档 1 单，耗时 24 ms` |
| 验收④ | ✅ 幂等：下一轮 `无到期数据，本轮未删除` / `无到期评估单，本轮未归档`（0 条 / 0 单） |
| 不误伤 | ✅ 三种反例全部未动：`PUBLISHED+未到期`、`VOID+过期`、`SUBMITTED+过期` |
| ⚠️ 连带发现 | **CR-M2-003**：`audit_log.sensitive` 为 MySQL 8 保留字、实体未反引号映射 → **审计留痕长期 0 行**（已修复 + 系统性扫描 + 回归测试 + 门禁金丝雀）。同源动作 ER-14 留痕一并恢复 |
| 任务数 | 1 壳 / 2 动作，**未达 ≥3 阈值，不迁 XXL-Job**（已回写 ADR-0004） |

---

## 卡 P1-5 · 其余端切真实接口（iOS / 微信 / 抖音 + 家属端 F3）

| 项 | 内容 |
|---|---|
| 编号 | **P1-5** |
| 类别 | 端侧联调（"② 解除 Mock"的延续） |
| 紧急度 | 中 —— 依赖 P0-2 安卓路径已跑通（**已满足**） |
| 依据 | P0-2 安卓端到端联调结论（2026-09-23）；PM："iOS/微信/抖音按安卓验证过的路径跟进；家属端 F3 报告/趋势接上" |
| 现状 | 安卓已完成真实接口闭环；其余端仍走 Mock。`RealApiClient` 中 **`getReports` / `getReport` / `getTrend` / `getCarePlan` / `getMyBindings` 仍委托 Mock**（属家属端 F3 的报告/趋势与照护视图） |
| 动作 | ① 复用安卓已验证的路径：`BASE_URL http://<host>:8080`（无 context-path）+ `/api/v1` 前缀 + `Bearer` 令牌 + 账密渠道登录（ADR-0005）；② 逐端替换 Mock 实现；③ 家属端 F3 接 `RealApiClient` 的报告/趋势/照护接口 |
| 已知坑（安卓踩过，可直接抄） | ① **API 28+ 默认禁明文**，需 `network_security_config.xml` 放行开发主机；② 令牌需在登录/恢复后**绑定到 API 客户端实例**，否则后续请求 401；③ 键盘弹收导致坐标漂移，端到端脚本每步须重新取元素位置 |
| 验收 | 每端至少跑通「登录 → 评估录入 → 分级 → 报告 → 复核发布」主链，并在 DB 侧双证（`eval_order` / `eval_report` / `eval_review_log`）；家属端可见报告与趋势且**不出现本地算分**（红线） |
| 实施状态 | ✅ **已实施并验收（2026-09-23）**；家属端 F3 部分**改判为后端供给缺口**，顺延至 **CR-M2-005** |

### 实施记录（2026-09-23）

| # | 动作 | 落地物 | 结果 |
|---|---|---|---|
| 1 | **后端 dev 渠道降级开关**（微信 / 抖音端登录的前置） | `ChannelDowngradeSwitch` + `AuthService.shouldDowngrade()` + `LoginResponse.channelDowngrade`；`yl.auth.channel-downgrade.enabled` 默认 false、仅 dev 覆盖 | 单测 **6/6**（含"去掉 `\|\| downgrade` 立即失败"的变异验证）；**FACE 越界发现并收窄为 WECHAT/DOUYIN 白名单** |
| 2 | **四渠道 curl 对照** | 见 CR-M2-004 | 7 项全符预期：ANDROID/IOS 成功且 `channelDowngrade=false`；WECHAT/DOUYIN **带口令**成功且 `true`；错口令 / 无口令 / 白名单外（FACE）**全部被拒** |
| 3 | **三端数据层**（每端 3 文件） | `utils/request.ts`（基准 + 雪花 id 精度保护 `requestWithText`）、`utils/real-api.ts`（真实实现，签名对齐 Mock）、`utils/api.ts`（一行重导出作切换开关） | 三端 `md5` **各只 1 个唯一值**（`request.ts eae7df44…`、`real-api.ts f41fd233…`、`api.ts 2a06d09a…`） |
| 4 | **页面 import 切换** | `mock-api` → `api`（`pages/**` 走 `../../utils/api`、`stores/**` 走 `../utils/api`） | 三端零残留引用；唯一代码改动面 = `utils/api.ts` 一行 |
| 5 | **iOS 端账密入口** | `pages/login/login.vue` 增"账号密码 / 短信验证码"切换（默认账密）＋ `stores/auth.ts` 的 `doPasswordLogin()` | 角色**取服务端 `roles[0]`**，端上不自行指定身份；登出同批清理 `tokenStore` 双令牌 |
| 6 | **渠道注入** | 三端 `.env` 的 `VITE_LOGIN_CHANNEL`（IOS / WECHAT / DOUYIN） | 产物实测：各端只含本端渠道码，**反证**（微信端产物无 `IOS`）通过 |
| 7 | **H5 逐端主链实跑** | `_验收工具/{h5-serve.js, h5-e2e.js, run-h5-acceptance.sh}`（同源静态服务 + `/api/v1` 反代 + Edge 无头驱动） | **三端全通**，各 4 次真实接口调用：`auth/login/{渠道}` → `evaluation/assessments` → `.../answers` → `.../submit`；结果页 **90/90 能力完好** |
| 8 | **DB 双证** | 三单各 26 项作答落库后，补做绑定报告 + 复核发布 | `eval_order` 3 行 `PUBLISHED`（`retain_until` = 2031-09-23，+5 年）；`eval_report` **3 行**（`RPe243182b-71f` 等）；`eval_review_log` **6 行**（`SUBMIT` by …001、`PUBLISH` by …002 —— 录入/复核互斥成立） |
| 9 | **形态冒烟** | `mp-weixin` / `mp-toutiao` / `app` 三形态构建 | 全部 `DONE Build complete.` |

**验收中暴露的两点（已处置）**

1. **FACE 渠道被误纳入降级范围**（影响面超出需求）：微信 / 抖音是端侧缺渠道，FACE 是**生物识别合规前置未办结而有意不接入**，两者性质不同 → 降级白名单收窄为 `WECHAT`/`DOUYIN`，并补第 6 格单测（FACE 同构场景对比）锁死。
2. **H5 验收必须跑在 CORS 白名单内**（环境约束，非缺陷）：后端 `yl.cors.allowed-origins` 默认仅 `http://localhost:5173,http://127.0.0.1:5173`，浏览器 POST 带 `Origin`，非白名单来源被 Spring 直接判 **403 Invalid CORS request**（实测 5180 端口）。故验收编排统一在 **5173 顺序复用**，不改后端配置以贴合默认部署口径。

**家属端 F3 改判说明**：`getReports/getReport/getTrend/getCarePlan/getMyBindings/requestBinding/getMessages` 与机构侧 `mockElders/mockHomeStats/mockCarePlan` **后端确实零供给**（`yl-modules` 无 elder/family 模块；`openapi/yl-api.yaml` 无 family 路径）。端侧已按"**显式委托 Mock + 逐条标注缺口**"处理（无法委托者显式返回空数组，不造数），并登记 **CR-M2-005** 承接后续替换。

---

## 卡 P2-6 · M2-S3（任务集 D）开工

| 项 | 内容 |
|---|---|
| 编号 | **P2-6** |
| 类别 | 主线开发 |
| 紧急度 | 低（**须在"解除 Mock"之后起**，PM 明确排位） |
| 前置 | P0-2 已完成；P0-3（CR-M2-002）建议**先于**任务集 D 中涉及权限的接口实施，避免 D 落地后返工 |
| 范围 | M2-S3 = 任务集 D（档案 / 报表域），**含 ER-14 适配器接入**（已并入本项，**不单独立项**） |
| 关联已有产出 | ER-14 三方绑定提案与 DDL 补丁（`docs/reviews/ER-14-*`）、ER-11 埋点字典、C4 报告域设计 |
| 开工前检查 | ① `CR-M2-002` 是否已会签（影响 D 的接口鉴权写法）；② 反向缺口 6 类接口是否随 D 一并补登契约（`CR-M2-001` §2.4b） |
| 验收 | 按任务集 D 的验收清单 + 新增接口全部带 `x-required-permission` 与数据域口径 |

### 实施记录（D 子卡）

| 日期 | 子卡 | 状态 | 产出 / 结论 |
|---|---|---|---|
| 2026-09-23 | **D-1 契约补登 + 评审** | ✅ 差异清单已产出 | `docs/change/D1-openapi-contract-delta.md`。**契约 22 操作仅 3 个与实现对齐**（9 漂移 / 10 未实现）；**后端 9 个端点契约完全缺失**（此前无人登记）；反向缺口 6 类中 R1 / R5部分 / R7 **实际已实现**。**★ 副产品：立卡 CR-M2-006** |
| 2026-09-23 | **★ CR-M2-006（建议插队）** | ✅ **已插队修复并实证（09-24，复验全绿）** | `CR-M2-006-report-pdf-authz-bypass.md` §八：`read()` 先查库再读文件 + `download()` 补 `report:view` + 3 个裸奔端点补注解；三层防回归（单测 / 控制器鉴权覆盖测试 / 冒烟 5 断言）。**跨机构 `reportNo` 实测落 `R.code=10004` 且响应体非 PDF 魔数**（复验 34 通过 / 0 失败 / 1 跳过） |
| 2026-09-24 | **D-1 裁决 + 契约冻结** | ✅ **5 项裁决全部通过（A/A/A/A/A），契约升 v1.1.0** | A 类 9 项对齐、C 类 9 项补登、B/D 类 21 项标 `planned`；新增 `x-data-scope`/`x-sensitive-fields`/`x-status` 扩展键；家属端 **F1~F9 契约先行**（路径统一 `/family/bindings/{relationId}/**`）；`getMessages()` 移出 D-4 |
| 2026-09-24 | **D-1 门禁落地** | ✅ | 新增 `scripts/verify-openapi.sh`（静态 5 组 8 断言，**漂移双向 = 0**）＋ `OpenApiContractTest`（SnakeYAML 真解析 + 反射漂移）＋ `ControllerAuthorizationCoverageTest`（全端点必挂 `@RequiresPermission`）；`verify-schema.sh` 78 → **81**（家属 `data_scope=5`、契约码 ⊆ 库码） |
| 2026-09-24 | **D-2 首交付物：数据域关系级** | ✅ **已完成**（提前于 D-2 主体） | `DataScope.FAMILY(5)` + 第三闸 `relationSegment`（直锚 / 反查锚 / 绑定表自身）+ **修正数值序陷阱**；种子 `FAMILY` 1 → 5（含存量库幂等 UPDATE）；单测 +8 例。**D-4 阻断面解除** |
| 2026-09-26 | **D-2 档案域后端（主体）** | ✅ **已完成并放行** | 4 端点 implemented（`elders` 建档/列表/详情 + `security/reveal`）；**+1 列 DDL**（`elder.user_id` 自见锚，解开 scope=1 下 ELDER「权限已授却不可达」的静默失效）；CR-M2-007 一并裁决实施（未认证统一 401）；门禁 148 例单测 / 82 断言 / 契约 8-8 / 冒烟 53；**QA 独立验证 0 缺陷 + 突变 3/3 全红**。见 `docs/change/D2-archive-domain-implementation.md`、`docs/reviews/D2-qa-report.md`、设计 `docs/design/D2-archive-domain-design.md` |
| 2026-09-26 | **D-3 报表域后端（B6~B10 + R6 组织管理）** | ✅ **已完成（2026-09-29 QA 放行）** | **范围经权威核对修正为 7 端点**（原记「报表域 3 个」）：B6 `GET /evaluation/tasks`、B7 `GET /rule/mapping`、B8 `GET /reports/verify`、B9 `POST /supervise/exports`、B10 `POST /supervise/report`、R6-1/R6-2 `GET /account/orgs[/{orgId}]`；**跨 evaluation / supervise / report / account 四模块**。**地基核查报告**：`docs/reviews/D3-foundation-check.md`。★ 揪出数据域 **2 处新缺陷**（`export_task` 无机构锚列 → scope=2 **静默放行**；`supervise_report` 未登记 → scope=1/5 **暗区**）；★ 附带发现后端**无 `SmsCodeService`**（连带影响 D-4 核身通道）。<br>**2026-09-28 裁决（用户拍板）**：① `export_task` → **A 补可空 `org_id` 锚列**（+1 列 DDL 已授权；NULL 行自然不可见 = 失败即拒；回填 + 登记 `ORG_COLUMN`；非破坏可回退）② 导出仅 **CSV**（BOM + 公式注入转义）③ `retain_until` **只写值**（清理排 P2-7）④ B8 **最小返回面**。**边界**：D-3 内**不碰文件删除**、**不新增 `@Scheduled`**（详见报告 §6.2）<br>**2026-09-29 收口**：四门禁**独立复跑** = 单测 **185/0**（基线 148）· schema **83/0** · 契约 **8/8**（implemented **33** / planned **10**，双向漂移 **0**）· 冒烟 **79/0**（基线 53）；QA 突变 **5/5 生效**（4 条变红 + M5 口径校验：无令牌 **401+10002**，反向证伪「无 `data:export` 打 exports → **200+20003**」——**CR-M2-007 未吞第二闸**）；**源码缺陷 0 条**；QA 自行修复脚本级 1 条低危（冒烟注入样本缺 `trap` → 中断会留脏单号，已补 `EXIT/INT/TERM` 还原）。QA 报告：`docs/reviews/D3-qa-report.md` |
| 2026-09-29 | **D-4 家属端 + 端侧切真** | 🔄 **实施中（D-4a 后端已完成 + QA 放行；D-4b 端侧已交付，待独立验证）** | **两半串行**：**D-4a 后端 11 端点**（F1~F9 九个家属操作 + `authLogout` 黑名单 + **新增** `POST /elders/{elderId}/user-binding`）→ **D-4b 端侧**（uni-app 三端家属端解除 Mock 委托，md5 三端同步）+ H5 `:5173` 家属主链 E2E（顺带闭环 **D3-R6** 的 FAMILY/ELDER 真实 HTTP E2E）。**地基核查报告**：`docs/reviews/D4-foundation-check.md`。<br>**★ 核查三发现（同一张表 `elder_family_bind`）**：① `reject`（ELDER，scope=1）落在 `SELF_DENY_TABLES` → **恒假 `(1=0)`，功能死路**；② `POST /family/bindings` 的核验查询在 scope=5 下 `elder.id IN (我绑定的老人)` → **目标老人尚未绑定 → 恒空**，核验无法执行；③ `elder_family_bind` **无 `org_id`** → ORG_ADMIN 在 `approve` / `GET /bindings` 上**跨机构静默放行**。<br>**★ 2026-09-29 裁决（用户拍板核身通道 + 本报告默认项）**：**核身 = 机构线下核验 + 代操作**（零新基建）；**短信通道立独立卡 P2-8**（含真实外发 + 手机号采集 + 合规评审，并把「核身强度分级」写成生产前置）；**否决**「复用二次验证语义」（它证明"当前会话操作者"≠"这位 elder 就是这个人"）。**零 DDL**：①②③ 全部用处理器内新增**反查锚**（`ORG_VIA_PARENT` / `SELF_VIA_PARENT`，经 `elder.org_id` / `elder.user_id` 收口）闭合——**schema 门禁保持 83**。**边界**：D-4 内**不碰文件删除**（`proof_cos_key` 生命周期归 P2-7）、**不新增 `@Scheduled`**、dev 补 `elder_dev`/`family_dev` 账号（同"上线前移除"红线）<br>**2026-09-30 D-4a 收口（QA 放行）**：四门禁 **独立复跑** = 单测 **255/0**（基线 185）· schema **83/0**（**零 DDL**，金丝雀 1）· 契约 **8/8**（implemented **44** / planned **0**，双向漂移 **0**）· 冒烟 **116/0/1 跳过**；Checkstyle/SpotBugs **0/0**；**QA 突变 10/10 逐条变红**（设计 §8.3 的 M1–M6 + 新增 M7 摘 `@InterceptorIgnore`、M8 加非标量方法、M9 复用分支改回 INSERT、M10 摘 `@SuppressFBWarnings` 证其承重）；**P0/P1 缺陷 0**，仅 2 条 P2 观察项。QA 报告 `docs/reviews/D4-qa-report.md`（304 行）；实施日志 `docs/reviews/D4-implementation-log.md`。<br>**★ 设计侧 5 处订正（实施期暴露，经我实读 DDL/字节码核实后裁定，落设计 §1.7/§1.8）**：**C1** `approve` 权限码 `account:family:bind` → **`account:org:manage`**（原前提"FAMILY 数据域不达"不成立 → 家属可自我放行到 `status=2`，绕过机构审核）；**C2** `approve`/`reject` 宽容为 `{0,1}→` + `create` 按 `hasLoginAccount` 落 0/1（原 `0→1` 无承载者 ⇒ 绑定链永不能生效）；**C3** 登记 D4-R7 并写死「ELDER E2E 不覆盖报告读取」；**C4** 读路径脱敏**不加 `isAvailable()` 守卫**（未装配 → `40001`，与 `ElderArchiveService#toView` 同口径）+ 两密钥启动前置写入 checklist；**C5** 「唯一绕过第三闸的读」的真实机制 = 接口级 **`@InterceptorIgnore(dataPermission="1")`**（全仓唯一注解级豁免；缺失则家属申请恒 `10004`）。<br>**★ 另 2 处设计错误**：**R-1** `elder` 表**无 `auth_end_at`** ⇒ 契约删 `UserBindingRequest.authEndAt`（不接收/不落库/不告警）；顺带钉死 `uk_elder_user` 唯一 ⇒ 老人↔账号 **1:1**（冲突 `10005`，绝不 500）。**R-2** `active_uk = IF(deleted=0,1,NULL)` ⇒ **只有 `deleted=1` 让位**，设计"新增行靠 `active_uk`"错 ⇒ 「重新申请」改为**复用活性行**（`UPDATE` 复位），仅 `status∈{0,1,2}` 才 `10005`；并钉死 **`deleted` 只表软删、不得表达业务状态**。**D-4b 待做**：uni-app 三端家属端切真（weixin-app 改一份 → md5 同步）+ H5 `:5173` 家属主链 E2E（闭环 D3-R6） |
| 2026-10-09 | **D-5 保留期链路收官** | ✅ **已完成** | 一次调度由 2 动作扩为 **4 动作**：① ER-14 三方绑定物理删除（既有）② ER-03 评估单转 `ARCHIVED`（既有）③ **ER-03 监管上报 `supervise_report.archived_at` 只读封存**（新，裁决 D5-D1，收口 **D3-R4**）④ **D4-R1 亲情绑定到期 `status 2 → 4`**（新，裁决 D5-D3）。**DDL**：`supervise_report` **+1 可空列**（补丁 `docs/reviews/D5-supervise-report-DDL-Patch-Proposal.sql`；内联 `02_schema.sql`）；schema 断言 **83 → 84**。**形态归一**（裁决 D5-D4）：ER-14 端口 `domain/thirdparty/ExpiredBindingPurger` → `domain/retention/`（纯移动 + 改 import，零行为变更）。**ADR-0004 回写**：任务数 **2 → 4** 的显式 XXL-Job 迁移复评（结论仍「不迁」，留痕见 `docs/ADR/0004` 补充节）。**残留登记（D5-D5）**：`elder_family_bind.auth_end_at` 全仓无写入口 ⇒ ④ 生产零命中，待有效期来源卡落地后自动生效 |
| 2026-10-10 | **P2-8-A 短信核身通道（骨架 + dev 日志桩）** | ✅ **已完成（QA 独立验证放行 + 断言缺口闭环）** | **裁决**：`E1` 先做 A 段（零外部依赖）· `E2` 新增 `LoginChannel.SMS` · `E3` 验证码走 **Redis TTL**（**不建表**）· `E4` 先出合规清单。**交付**：契约 **v1.1.4**（+4 操作 SMS-01/02/03/04 → implemented **44→48**；★ 一并订正 `POST /auth/login/{channel}` 的 **既有枚举漂移** `PASSWORD/APPLE` → `IOS/ANDROID`）；`ErrorCode` +6（20004–20009）；`yl-common` 5 端口 + `yl-infrastructure` 6 实现（`RedisSmsCodeStore` **Lua 原子比对并焚毁**、captcha/ticket 走 **GETDEL**、`AwtCaptchaRenderer` **零新依赖**、`LoggingSmsSender` / `UnavailableSmsSender` fail-closed）；`SmsCodeService`（频控 5 维 + 图形验证前置 + 锁定 + 一次性核销 + 失败不退）+ `SmsController` + `AuthService#loginByTicket`。**★ 零新表**（schema 86 不变，印证 Redis 裁决）。**★ 6 处与卡面/方案文档的偏离**已显式登记（含 **Redis 键用 `phone_hash` 摘要而非明文手机号**、**验证码值存 `sha256(code)`**、**错误与过期同码**、**不自动建号**）。**门禁（lead 独立复跑）**：单测 **317/0/0/Skipped 1**（基线 282）· 契约 **8/8（48/0 漂移 0）** · schema **86/0** · 既有冒烟 **119/0/0（无回归）** · 新增 `smoke-sms-chain.sh` **8/0/1 跳过**。**QA**：`docs/reviews/P2-8-qa-report.md`（15 条突变，无假红；暴露 3 处断言缺口 M3/M4/M13 → **已同批补强并用突变反向证明**，见该报告附录 C）。**合规清单**：`docs/reviews/P2-8-compliance-checklist.md`（E4）。**设计**：`docs/design/P2-8-sms-verification-design.md`。**残留**：R1 自动建号角色归属待产品裁决 · R2 真实通道 = P2-8-B · R3 端侧解 Mock = P2-8-B · R6 审计留存口径（180 天 vs 3 年）待裁决 · R8 契约 `SMS` 渠道语义已在 description 声明 |
| 2026-09-26 | **CR-M2-007（复验副产品）** | ✅ **已裁决并实施（随 D-2）** | 未携带 `Authorization` 原为 **403**（Spring 默认入口点）、凭据无效为 **401**（`JwtAuthFilter`），而 `SecurityConfig` 注释承诺"未认证统一 401 + 统一响应体"。**裁决：统一 401** —— `filterChain` 补 `exceptionHandling`，`RestAuthenticationEntryPoint`→401+`R(10002)`、`RestAccessDeniedHandler`→403+`R(FORBIDDEN)`。**职责边界已写死**：入口点/拒绝处理器只管过滤器链内异常；`PermissionAspect` 的拒绝仍走 `GlobalExceptionHandler` = 200+code（无权 20003 / 缺二次验证 10008），两条路径不重叠（已由 QA 反向证伪：无 `data:reveal` 的角色调 reveal → **200+20003，非 403**）。爆炸半径为零的依据：全仓原无任何「无令牌→403」断言。见 `CR-M2-007-unauthenticated-status-code-split.md` |

---

**P2-8 断言缺口闭环 + 「合规红线守卫 · 突变反证」立规（2026-10-10，用户 Step 2 / Step 3 指令）**

| 项 | 内容 |
|---|---|
| **背景** | QA 独立验证暴露 **3 处断言缺口**：`M4` 「Redis 键不含明文手机号」**无任何断言**；`M3` `phone:60s` 频控维度无断言；`M13` `LoggingSmsSender` **零测试**（dev 开关只有 yaml 值门禁、无行为门禁）。把 `hashPhone` 改成 `return phone;`（**明文手机号进 Redis**）门禁**全绿**。 |
| **M4 补强**（同时覆盖 M3） | `SmsCodeServiceTest#storageAndRateLimitKeysNeverContainPlaintextPhone` 按用户指定口径**收紧**：`ArgumentCaptor<String>` 捕获 `codeStore.save(...)` 第一参 → **`assertEquals(codec.hashForSearch(PHONE), storedHash.getValue())`**（★ 钉死期望摘要值，**不用**「不等于明文」这类弱否定）+ 断言手机号维度**恰为 3 个**（60s/1h/1d）且**注入的 `sms:rl:ph:` 维度一律含该摘要**。 |
| **M13 补强** | 旧 `SmsSenderTest` **拆分** → `LoggingSmsSenderTest`（`dev-log-code=false` 日志**不含**验证码明文、`true` 含且带 `[DEV-ONLY]`）+ `UnavailableSmsSenderTest`（fail-closed 专用错误码 + 日志含服务商与**掩码**手机号、绝不含验证码）；抽出包私有工具 `LogCapture`（logback `ListAppender` + `try/finally` detach 并还原 level）。 |
| **突变反证（4/4 全红）** | `scripts/dev/p28-mutation-recheck.py`：`M4'` `hashPhone → return phone;` → 红点 `storageAndRateLimitKeysNeverContainPlaintextPhone`；`M3'` 摘 `phone:60s` 维度 → 红点同名；`M13'` `dev-log-code=false` 仍打印验证码 → 红点 `hidesCodeWhenDevLogCodeDisabled`；**`M14'`（新，Step 3 红线类）摘 `MaskUtils.maskPhone` → 红点 2 条**。**4/4 RED，md5 逐字节还原一致，`git status` 无残留**。 |
| **门禁** | 补强后 `mvn -o verify` = **317/0/0/Skipped 1**（与 P2-8-A 基线**持平**：旧 `SmsSenderTest` 4 例拆为 `LoggingSmsSenderTest` 2 + `UnavailableSmsSenderTest` 2，**用例数净变化 0，质量提升**；M4 为既有用例内**断言收紧**，不改用例数）· 契约 **8/8** · schema **86/0** · 冒烟 **119/0/0**。★ **门禁只升不降** 满足（持平即不降）。 |
| **★ Step 3 立规** | 新增 `docs/quality/red-line-guard-mutation-proof.md`（**YL-M2-QG-REDLINE-MUTATION** v1.0）：**任何承载合规红线的行为（加密/哈希/脱敏/抑制/权限/留存/外发/幂等）新增或修改时，必须随批附一条「摘掉守卫即变红」的断言，并用一次真实突变证明它确实变红。** 原「新增 retention SQL 必须补 IT」规矩**扩展为 R6 的一个特例**，非取代。含 **§3.2 判定器必须自证**（★ 本轮事故：`subprocess(text=True)` 按 UTF-8 解码 Windows `mvn` 的 **GBK** 输出 → 抛 `UnicodeDecodeError` ⇒ **判定分支从未执行** ⇒ 3 条该红的突变全被误判 GREEN；修法 = 读字节 + `errors="replace"` + **主判据用退出码**）、§3.1 三参数（`spotless.check.skip` / `checkstyle.skip`+`spotbugs.skip` / `surefire.failIfNoSpecifiedTests`）、§3.3 还原纪律、§3.4 反面清单。 |
| **复发史** | **第三次**：D5-R1（SQL 条件在 mock 下不可见）→ P2-7-R6（`expire_at IS NOT NULL` 行为层不可区分）→ P2-8 M3/M4/M13。共性 = **守卫是真的，断言是空的**。 |

---

**D-1 裁决结果（2026-09-24，全部按默认建议）**：① 契约路径基线 = **A 存量向实现对齐**（附带定 `orderId`=写入轴 / `reportNo`=读取轴）② 数据域语义 = **A 新增 `DataScope.FAMILY(5)` 关系级**（已实施）③ 家属读接口权限码 = **A 复用 `report:view`**（零新增码）④ `getMessages()` = **A 移出 D-4**（V2）⑤ 老人"确认" = **A 沿用现有码**（仅 `:reject`）。

**验收期勘误（2026-09-24，契约 v1.1.0 → v1.1.1）**：CR-M2-006 复验报「34 通过 / 2 失败」，排查确认**不是越权回退**，而是**契约与断言写错了层级** ——
本服务业务异常一律 **HTTP 200 + `R.code`**（`GlobalExceptionHandler#handleBiz` 无 `@ResponseStatus`），按 HTTP 状态码断言会同时造成**假红**与**一条恒真的假绿**
（「还原后可下载（HTTP 200）」成功/失败同为 200）。已把契约该端点 `responses` 由 `'404'` 改准为 `200/401/403`、并把「统一响应」口径写死；
冒烟断言下沉到 `R.code` + **PDF 魔数**双层判定 → 复跑 34 通过 / 0 失败。**教训：本项目"拒绝"看 `R.code`，不看 HTTP 状态码；二进制端点必须验魔数。**

---

## 依赖关系总览（PM 原话结构，已冻结）

```
#57 决策（ADR-0005，已闭环）
        └─> 安卓联调（BASE_URL / 限流）  ← P0-2 已完成
                    └─> 其余端跟进      ← P1-5

登录链路（已就绪）
        └─> RBAC 鉴权补位             ← CR-M2-002（P0-3，已登记待排期）

ARCHIVED + ER-14
        └─> 同一个 ADR-0004 调度任务   ← P1-4b
```

## 排期建议（不占用关键路径）

| 顺位 | 项 | 理由 |
|---|---|---|
| 1 | **CR-M2-002（P0-3）** | 安全项，前置条件已满足；越晚做，越权窗口越长 |
| 2 | **P1-4a 容器字体** | 改动小（Dockerfile + 环境变量），但线上必挂；先做收益/成本比最高 |
| 3 | **P1-5 其余端** | 路径已被安卓验证，可并行推进 |
| 4 | **P1-4b 归档** | 需 DDL 补丁 + 门禁同步，走既有补丁流程 |
| 5 | **P2-6 任务集 D** | 主线开发，量大；建议在 1 之后起，避免接口鉴权返工 |

---

## 卡 P2-7 · 孤儿文件回收（保留期链路扩展）

| 项 | 内容 |
|---|---|
| 编号 | **P2-7** |
| 类别 | 数据治理 / 存储清理（合规 + 运维） |
| 紧急度 | 低 —— 不阻塞召回；磁盘占用可控，但**随 D-3 导出落地会放大** |
| 排位 | **排在 D-5 之后**（用户 2026-09-26 指令 #3） |
| 现状 | 报告 PDF 落 `%TEMP%/yl-reports/report/`；**库重建 / 报告软删 / 作废后文件不清理** → 永久孤儿。2026-09-26 实测：库内 `eval_report` **0 行** ⇒ 目录下 **12 个 PDF 全为孤儿**，已**人工移入回收站**（12/12，未清空回收站、可还原），目录剩 0。D-3 的 B9 将新增 `export/<taskNo>.<ext>` 产物，**同源问题会放大** |
| 根因 | 回收只在「**DB 行**」维度存在（软删 + `retain_until` 归档），「**文件**」维度**无任何回收方**；`DataPermissionInterceptor` 只改写 SQL，对文件系统零感知 |
| 可复用范式 | `RetentionChainScheduler`（ADR-0004，全仓唯一 `@Scheduled`：ER-14 物理删除 + 超期评估单归档 + 监管上报封存 + 亲情绑定流转 = **4 个动作**）。⚠️ **任务数阈值**：现 **4** 个动作（D-5 落地后），本卡落地后 = **5** → **触发 ADR-0004「≥3 须重新评估是否迁 XXL-Job」的复核点**，实施时须显式复评并回写 ADR（D-5 已做一次 2 → 4 的复评，见 `docs/ADR/0004` 补充节） |
| 动作 | 新增保留期链路**第 5 个动作**：扫描存储根目录 ↔ **反查 DB**（`eval_report.pdf_cos_key` / `export_task.file_cos_key`），**只回收无 DB 行引用的文件**；限批 + 幂等 + 失败不抛；删除走**回收站 / 隔离目录**（**不直接 `rm`**），保留可还原窗口 |
| 红线 | ① **绝不**先删文件后删行；② 必须**先反查 DB 确认无引用**（防误删在用文件）；③ 遍历限定在配置的存储根内，**禁止**通配删除；④ 保留可还原窗口（承接本次"移入回收站"的口径） |
| 前置 | D-3 落地（确定导出产物目录与键名口径）→ 故 D-5 之后 |
| **范围补充（2026-09-28）** | **`export_task.expire_at` 的「过期清理」语义整体归本卡**（含导出产物的扫描/回收）。**D-3 只负责写入 `expire_at` 值，不实现任何删除/回收/定时清理**——避免与 P2-7 的回收站与限批口径打架；D-3 亦**不得**新增 `@Scheduled`（ADR-0004 唯一调度仍在 `RetentionChainScheduler`） |
| 验收 | ① 构造孤儿文件 → 跑一次 → 孤儿被回收，**有 DB 引用的文件未动**；② 重复跑幂等（第 2 次 0 个）；③ 与现有 4 个动作在同一次调度内完成；④ 还原路径可用 |

### 实施记录（2026-10-09）

| 项 | 结论 |
|---|---|
| 实施状态 | ✅ **已完成（2026-10-09，含 QA 独立验证 PASS）**：保留期链路第 5 个动作落地（`OrphanFileSweepTask`），ADR-0004 任务数 **4 → 5** 的第 2 次复评结论仍「不迁 XXL-Job」 |
| 落点 | `yl-module-report`（导出域）：`service/retention/OrphanFileSweepTask` + `domain/retention/{RetentionReferencePort,ExpiredExportTaskReaper}` + `mapper/RetentionReferenceMapper`（手写 `@Select`/`@Update`，**显式 `deleted = 0`**） |
| 存储根唯一真源 | `yl-api` 新增 `cn.yl.api.storage.ObjectKeyResolver`（先例 `PdfFontLocator` 同址）；`ReportPdfService` / `SuperviseExportService` / 清扫器**三处共用**，防"扫的根 ≠ 写的根"静默漂移（D3-A） |
| 口径（用户裁决） | **D1-B** 到期导出任务先置 `status = 4` 再回收；**D2-A** 报告在用集合 = `deleted=0` 的 `report_no` 推导键 **∪** 非空 `pdf_cos_key`；**D4-A** 回收至各根下 `.trash/<yyyyMMdd>/<前缀>/`（同盘 `Files.move`、可还原） |
| 引用源登记 | 全仓 6 个 `*cos_key` 列全部登记为引用源，并加 `verify-schema.sh` 断言（计数 + 逐列集合），新增引用源列须同步登记否则门禁变红 |
| 门禁 | `RetentionChainDbIT` 新增 **T7**（导出任务到期 `status → 4`）/ **T8**（报告投影排除 `deleted=1`）/ **T9**（导出投影不含 `status=4`）；`RetentionChainSchedulerTest` 扩为 5 动作 / 五计数；新增 `OrphanFileSweepTaskTest`（11 例）；schema 断言 **84 → 86** |
| 收口残留 | **D3-R2**（孤儿 CSV）、**D4-R4**（`proof_cos_key`）已由 P2-7 收口（见下方处置表） |
| 门禁复跑（lead 独立执行） | `mvn -o verify` **×2 均 BUILD SUCCESS**，聚合 **270 → 282 / 0 / 0 / Skipped 1**；`verify-openapi.sh` **8/8**（零漂移）；`verify-schema.sh` **86/0**；`verify-retention-chain.sh` → `RetentionChainDbIT` **9/0/0** |
| ★ 门禁 flaky 事故（已修） | 实施者自报「全绿」，**lead 独立复跑直接 `BUILD FAILURE`**：`OrphanFileSweepTaskTest.skipsSymbolicLinks` 在实施者处 **Skipped**、在 lead 处 **Failures** ⇒ 同一提交两种结果。**根因（实测坐实）**：本机 Windows 文件系统**不支持真正的符号链接且"假成功"降级** —— `Files.createSymbolicLink` / `cmd mklink` 均报成功却落成 **0 字节普通文件**（`readSymbolicLink` 抛 `NotLinkException`、`isSymbolicLink` 恒 `false`）。**修复**：判据改为 **`Files.isSymbolicLink` 确定性跳过**（Windows 稳定跳过、Linux CI 仍真跑）；并删除代码/文档中"符号链接**天然**跳过"的**过度声明**（§4 平台限制）。修复后连跑两次皆 `BUILD SUCCESS` |
| QA 独立验证（PASS） | 报告 `docs/reviews/P2-7-qa-report.md`：设计 §7 **九条判据独立通过**；**突变 7 条**（M1 摘 `deleted = 0`→T8 红｜M2 `status <> 4` 恒真→T9 红｜M3 摘 `status <> 4`→T7 红｜**M4 摘 `expire_at IS NOT NULL`→当时不变红**｜M5 `Files.move`→`Files.delete`→2 例红（**证明"可还原窗口"确被断言**）｜M6 摘 `isRegularFile(NOFOLLOW)`→子目录用例红｜M7 递归 `Files.walk`→"只列一层"红），**逐字节还原、md5 全复归**；**无假绿**（`check()` 真比较、冒烟 119/0/0 且自证有断言引擎、二进制端点验魔数）；安全红线 4 条 + `resolve()` 越界实测抛 `BizException(200+10001)` |
| 收尾补强（lead，闭环 P2-7-R6） | QA 发现 M4「摘 `expire_at IS NOT NULL` 门禁不变红」且该 Mapper **无静态守卫**（T5 原只覆盖 4 个既有 Mapper）⇒ 把 **T5 静态守卫扩到第 5 个 Mapper**，并**用突变反向证明守卫有区分力**：摘掉该条件后 `Failures: 1`、红的正是 `t5SqlGuardsPresentForAllFourMappers`（此前为绿）。★ 方法注：突变跑测试**必须加 `-Dspotless.check.skip=true`**，否则行长度变化会先触发 Spotless 违规、`BUILD FAILURE` 与断言无关（本轮一度踩到，属假阳性） |

---

## 卡 P2-8 · 短信核身通道（真实外发）—— 生产前置

| 项 | 内容 |
|---|---|
| 编号 | **P2-8** |
| 类别 | 合规 / 外部通道接入（**触发合规评审**） |
| 紧急度 | 中 —— **不阻塞 M2/dev**（D-4 用机构线下核验替代），**阻塞生产上线** |
| 立卡依据 | 用户 **2026-09-29** 裁决：「短信通道不造轮子也不假装存在……涉真实外发通道 + 手机号采集，触发合规评审，不是顺手能加的依赖」 |
| 现状 | **2026-10-10：P2-8-A（后端骨架 + dev 日志桩）已落地并 QA 放行**（见上方完成表）。后端具备完整 SMS-01~04 端点、频控、一次性核销、fail-closed 通道端口；**零新表**（验证码走 Redis TTL）。端侧 `sendSmsCode` / 短信登录**仍委托 Mock（固定码 `123456`）**，待 **P2-8-B** 接真实通道后解除 |
| 为什么不能顺手加 | ① **真实外发通道**须对接运营商/云通信（签名、模板报备、下发频控、失败重试）；② **手机号采集**进入个保法「敏感个人信息」范畴 → 需单独告知同意留痕 + DPO 签署；③ 端侧 `FACE` 核身有意不接入，短信若接入需重新过一遍核身强度评审 |
| 动作（建议） | ① 按 `15_短信验证码通道技术方案.md` 落地：验证码表（`ADR-0003` 口径：雪花 id / 0 外键 / `DATETIME`）+ `SmsCodeService`（下发/校验/频控/一次性消费）+ 通道适配（配置化，dev 走日志桩、生产走真实通道）；② 接入 `LoginChannel.SMS` 的 `passwordSupported=false` 分支；③ 端侧 `sendSmsCode` / 短信登录解除 Mock 委托 |
| **生产前置（本卡与 D-4 的接口）** | **核身强度分级**须先定稿并回写：**M2/dev = 线下核验（机构代操作）** < **生产 = 短信核身（本人自主认领）**。即 D-4 落地的 `elder↔user` 绑定 = **机构代绑**；「老人自主认领 / 家属自主绑定」的**开放条件**是本卡落地 + 分级口径经 DPO 签署。**未定稿前不得在生产开启自助核身** |
| 红线 | ① **不得**在 D-4 内实现短信（用户明令）；② 验证码**一次性消费 + 失败不退**（承 `CR-M2-002` §8.7 的二次验证同一口径）；③ 手机号不得明文落库（承 `SensitiveFieldCodec` 红线）；④ 下发接口必须限流（防短信轰炸与费用攻击） |
| 排位 | **D-5 之后**（与 P2-7 同批），实际以产品上线节奏为准 |

---

## 残留清单处置（2026-09-26 裁决）

| 残留 | 内容 | 处置 | 依据 |
|---|---|---|---|
| **R1** | `elder↔user` 绑定写入流程未实现 → ELDER 端真机 E2E 不可达 | **升格并入 D-4**（与家属绑定**一次设计**，同属身份关联域） | 用户 2026-09-26 指令 #2 |
| R2 | `dataReveal.bizType=USER` 不支持（返 `10001`） | 保持残留（不加权限码、不动账号域） | D-2 登记 |
| R3 | `audit_log.biz_id` 未填 | 保持残留（`action`+`biz_type`+`sensitive=1` 已可留痕） | D-2 登记 |
| **R4** | 孤儿 PDF 机制未修（已人工清一次） | **立独立卡 P2-7**（见上），排在 **D-5 之后** | 用户 2026-09-26 指令 #3 |
| R5 | `consent_record` 锚为 `subject_type+subject_id` 而非 `elder_id` | 保持残留（家属端本期无该读取需求） | D-1 残留 |
| **D3-R1** | B10 幂等为 `SELECT → INSERT/UPDATE`（**非原子 upsert**） | 保持残留：**顺序**重放安全（冒烟实证），**极端并发**同月同机构双写才可能撞 `uk_month_org` → 500。若后续需强幂等再改 `INSERT ... ON DUPLICATE KEY` | D-3 QA §5.2 |
| **D3-R2** | B9 **先落盘后落库、无事务** → 落库失败留**孤儿 CSV** | **✅ 已由 P2-7 收口（2026-10-09）**：保留期链路第 5 个动作 `OrphanFileSweepTask` 按「存储根 ↔ 反查 DB」回收无引用文件（`export/<taskNo>.csv` 无对应 `export_task` 行即回收），移入 `.trash/` 可还原、**不 `rm`**；限批 + 幂等 + 失败不抛 | P2-7（原 D-3 QA §5.3） |
| **D3-R3** | `format ∈ {XLSX,PDF}`、`exportType ∈ {REPORT,STAT}` 一律 `10001`；异步大导出（>10000 → COS）无实现 | 保持残留（**零新增依赖**是有意取舍；需 XLSX 时另立卡评估 POI） | D-3 裁决 2 |
| **D3-R4** | `supervise_report` **未被 `RetentionChainScheduler` 覆盖**（`retain_until` 只写值、归档未接线） | 归 **D-5**（保留期链路），D-3 不扩调度边界 —— **2026-10-09 已由 D-5 收口**：新增 `SuperviseReportArchiveTask` 接入链路，到期写 `archived_at`（**只读封存、行保留、`status` 上报状态不变**；裁决 D5-D1） | D-3 裁决 3（QA C7 已证实）；D-5 裁决 D5-D1 |
| **D3-R5** | B10 **未加 `@Audit`** → 监管上报无留痕 | 保持残留（设计 §2.2 记为"可选"） | D-3 QA §5.6 |
| **D3-R6** | **仅** `assessor_dev`(ASSESSOR/2) 与 `orgadmin_dev`(ORG_ADMIN/2) 做过真实 HTTP E2E；`SUPERVISOR(4)`/`ELDER(1)`/`FAMILY(5)` 未验 | 由 D-4 端侧 E2E 覆盖；当前依赖 `DataScopePermissionHandlerTest` 的 SQL 片段断言 | D-3 QA §5.7 |
| **D3-R7** | B8 未单独验 `status=2`（已作废）的 `valid=false`（单测只覆盖 `status=0`） | 保持残留（语义同属 `valid=false` 分支） | D-3 QA §5.9 |
| **D4-R1** | `elder_family_bind.auth_end_at` 到期后 `status` **不流转为 4**（无调度覆盖；`RetentionChainScheduler` 只覆盖 ER-14 三方绑定 + ER-03 评估单归档） | **2026-10-09 已由 D-5 处置**（裁决 **D5-D3**）：新增 `FamilyBindingExpireTask` 接入链路，到期 `status 2 → 4`（**只改 `status`、绝不碰 `deleted`**，守护「重新申请复用活性行」语义）。安全性本就不依赖该流转（第三闸子查询含 `(auth_end_at IS NULL OR > NOW())`，fail-closed）。**残留登记（D5-D5）**：`auth_end_at` 全仓无写入口 ⇒ 本动作**生产零命中**，待有效期来源卡（`elder_authorization`/admin）落地后自动生效 | D-4 地基核查 §7.1；D-5 裁决 D5-D3 / D5-D5 |
| **D4-R2** | 端侧「手机号搜索老人」**本期降级为不支持**（契约 9 操作里**无「标识 → elderId」解析通道**） | 端侧隐藏该入口；扫码内容改为承载平台约定标识（`elderNo`/`elderId`）。解析端点若需要，另开卡（本质是存在性探测，M2 不宜开放） | D-4 地基核查 §7.6 |
| **D4-R3** | `elder_authorization`（第三方**范围化授权**）**未接线** | 本期只写 `elder.user_id`；范围化授权另开卡 | 设计 §1.2 |
| **D4-R4** | `proof_cos_key` **只存键不落盘**（全仓无 COS 实现） | **✅ 已由 P2-7 收口（2026-10-09）**：`elder_family_bind.proof_cos_key` 已**显式登记为引用源列**（`verify-schema.sh` 断言全库 6 个 `*cos_key` 列 == 登记集）——即使将来接 COS / 补写入实现，其指向的文件也**不会被清扫器误删**；当下无对应文件，登记为**防御性**（见残留 P2-7-R2） | P2-7（原 D-4 地基核查 §7.2） |
| **D4-R5** | **短信核身通道未实现**（`verifyMethod=PHONE` / 老人自主认领） | 立独立卡 **P2-8**（含真实外发通道 + 手机号采集 → **合规评审**）；「核身强度分级」为该卡**生产前置**。M2/dev 走**机构线下核验 + 代操作** | D-4 地基核查 §6.1 |
| **D4-R6** | 绑定关系**不落 `consent_record`**（有意） | 保持残留：语义为「隐私政策告知同意」（ER-08），与「亲属/监护授权」**不同构**，锚 `subject_type+subject_id`（R5）无法表达相对方。留痕走 `audit_log`（申请/审核/拒绝/解绑，解绑 `sensitive=1`） | D-4 地基核查 §5 |
| **D4-R7** | **ELDER(scope=1) 读不到自己的评估报告**（`eval_report`/`report_dimension_score`/`eval_answer` 在 scope=1 下恒假） | **保持残留**（既有，非 D-4 缺陷）：缺「评估-报告→老人本人」锚；修它=给老人开新读能力且爆炸半径=评估域全部读端点对 ELDER 开放。**D3-R6 口径：ELDER E2E 不覆盖报告读取**。另开卡（D-5/P2） | 设计 §6.6（★C3） |
| **D4-R8** | **敏感字段密钥相关残留**：① dev 无密钥 → 核验/读路径均 `40001`；② 生产**密钥轮换** → 旧密文不可解 → 读路径 `40001` | ① 属预期环境口径（`D4-e2e-checklist.md` 写死启动前置：导出 `SENSITIVE_FIELD_KEY`/`SENSITIVE_FIELD_HASH_KEY`）；② 本卡无 KMS 接入、不实现双密钥并行期，须与 **ADR-011** 同批设计 | 设计 §1.1c / §1.7（★C4） |
| **D4-R9** | **老人↔账号绑定无「有效期」语义**（`elder` 表无 `auth_end_at` 列；契约 `authEndAt` 已删） | 绑定=**长期有效**，撤销靠显式解绑（④闸）。需「范围化/限期授权」走 `elder_authorization`（`valid_from`/`valid_to`/`is_permanent`，ER-07）——**另开卡**（与 D4-R3 同批） | 设计 §1.8 R-1（实施期裁决） |
| **D4-R10** | **未知字段被静默忽略**（QA O-1，P2）：请求体若仍带已删的 `authEndAt`，Jackson **静默忽略**并返回 `code=0` | **保持残留**（**不**加 `additionalProperties: false`）——本工程 Jackson **未开** `FAIL_ON_UNKNOWN_PROPERTIES`，若契约收紧而实现仍返回 200，会造成**契约≠实现**（违"契约 = 实现镜像"门禁哲学）。改为**契约 `description` 显式声明**「本端点不承载授权有效期，字段已移除；客户端仍发送将被忽略」。**端侧（T09）不得发送该字段** | QA 报告 §5 O-1 |
| **D4-R11** | **趋势端点不下发维度分**（`FamilyTrendPointView` 无 `dimensionScores`）⇒ 端侧 `family-trend` 维度分**恒空态** | **保持残留**：`D4-frontend-migration.md` §2.5 原假设「`dims` 由后端 `dimensionScores` 映射」**与后端 DTO 不符**（D-4b 实读证伪）。维度分只有「报告详情」可得；端侧按"缺失→空态、不造比例还原"处理。要趋势图带维度须扩 DTO → **超本卡范围，另开卡** | 设计 §2.5 订正 |
| **R6** | 二次验证凭据在**业务失败后也被消费** | **裁决：维持现状（防重放优先）+ 写入显式口径** → `CR-M2-002` §8.7 | 用户 2026-09-26 技术倾向 |
| **D5-R1** | **保留期链路的 Mapper 手写 SQL 条件无自动化回归守卫**（QA 突变实证：摘掉 `archived_at IS NULL` / `status = 2` / `deleted = 0` / 改 `SET deleted=1`，`mvn -o verify` **仍 270/0 SUCCESS**）—— 单测一律用 Fake/mock 端口，**永不触达真实 SQL**；这些约束目前**只靠一次性实跑**证明 | **✅ 已闭环（2026-10-09）**：落地 **DB 级可重复门禁** 三件套 —— ① IT 类 `yl-bootstrap/src/test/java/cn/yl/bootstrap/retention/RetentionChainDbIT.java`（T1–T4 行为断言 + T5 SQL 守卫存在性；`*IT` 不进默认 surefire）；② Maven profile `db-it`（`yl-bootstrap/pom.xml`，surefire `includes=**/*IT.java`）；③ 脚本 `scripts/verify-retention-chain.sh`（source 凭据文件、不内联口令；失败 `exit 1`）。**种数据→调真任务→读真实 DB 断言→按 id 前缀自清理**，幂等可重复、连跑两次皆绿。**4 个 SQL 条件突变均变红**：摘 ④`deleted=0`→**T4 红**；④`SET status=4, deleted=1`→**T4 红**（deleted/active_uk 断言）；摘 ③`archived_at IS NULL`→**T3+T5 红**；摘 ②`status='PUBLISHED'`→**T2+T5 红**；摘 ①`retain_until IS NOT NULL`→**T5 红**（该守卫对 NULL 的比较在 SQL 三值逻辑下与 `<` 行为等价、行为层恒无差异，故由静态守卫 T5 兜底）。`mvn -o verify` 基线保持 **270/0**。**2026-10-09 追加收尾（用户裁决 ⑦-1/⑦-3/⑦-4）**：① **T5 保留** 并新增 **T6「NULL 到期语义」行为用例**（三张表各一行，断言到期时间列 IS NULL ⇒ 永不被删除/封存/流转）——T5 守文本、T6 守语义，二者互补；② `db-it` profile 固定 surefire `<version>3.5.4</version>`（消 “plugin.version is missing” WARNING，与 Maven 3.9.16 超 POM 默认**同版本、零行为变更**）；③ 突变工具**收编入库** `scripts/dev/`（`run-retention-mutations.sh` + `mutate-retention-sql.py`，脚本头部标注「开发工具，非门禁」）。**门禁复跑：`RetentionChainDbIT` 6/0 全绿、`BUILD SUCCESS`、WARNING 归零。** | D-5 QA 报告 §5-1（突变 M1–M4）；用户 2026-10-09 裁决 ⑦-1/⑦-3/⑦-4 |
| **D5-R2** | 限批循环在「待处理数恰为 `batch-size` 整数倍」时**多跑一轮空批**（如 4 行 / batch=2 → 批次 3，末轮空） | **保持残留**：结果正确（幂等，空批无副作用），仅多一次空查询；不值得为它改循环结构 | D-5 QA 报告 §5-2（低危） |
| **P2-7-R1** | 隔离目录 `.trash/` **无自动过期清理**（会随回收累积） | **保持残留**：保留可还原窗口优先；后续可为其加"隔离 N 天后清空"的**独立动作**（**会再推高动作数** → 届时按 ADR-0004 触发迁移复评） | 设计 §10 P2-7-R1 |
| **P2-7-R2** | `proof_cos_key` / `avatar_cos_key` / `photo_cos_key` / `eval_evidence.cos_key` **无对应文件**，登记为引用源属**防御性** | **保持残留**：登记成本≈0，且是接 COS 后防误删的前提；已在设计 §2 说明 | 设计 §10 P2-7-R2 |
| **P2-7-R3** | 导出文件**无下载端点** ⇒ 其"在用"仅由行存在性（`status ≠ 4`）定义 | **保持残留**（与 D3-R3 同批：异步大导出无实现）；D1-B 已让 `expire_at` 不再是死列 | 设计 §10 P2-7-R3 |
| **P2-7-R4** | 回收动作**不与 DB 事务同界**（先改 `status` 再移文件；中间崩溃 ⇒ 行已 `4`、文件仍在根内） | **保持残留**：定性为"**最终收敛**"（下次扫描仍会回收），非缺陷；反之（先移文件后改行）才是真不一致 | 设计 §10 P2-7-R4 |
| **P2-7-R5** | **符号链接「跳过」分支本机不可构造输入**：Windows 文件系统不支持真符号链接（`createSymbolicLink`/`mklink` "假成功"→ 0 字节普通文件）⇒ **本地门禁恒跳过**（Skipped 1） | **保持残留**：判据已改为 `Files.isSymbolicLink` **确定性跳过**（不再 flaky）；该分支由**支持符号链接的 Linux CI** 覆盖。**不改断言去迎合平台**（把 `swept` 改成 1 会把平台假象固化成断言） | P2-7 QA §7 R5；设计 §4 平台限制 |
| **P2-7-R6** | `RetentionReferenceMapper.markExpired/countExpired` 的 `expire_at IS NOT NULL` **行为层不可区分**（`NULL < cutoff` 求值 UNKNOWN 本就排除）**且无静态守卫**（T5 原只覆盖 4 个既有 Mapper） | **✅ 已闭环（2026-10-09）**：`RetentionChainDbIT#T5` 静态守卫**扩到第 5 个 Mapper**；并**用突变反向证明有区分力**（摘该条件 → `Failures: 1`，红点正是 T5；此前 M4 为绿）。与 D-5 的「①`retain_until IS NOT NULL` 只能靠 T5 兜底」**同一处置范式** | P2-7 QA §7 R6；设计 §10 P2-7-R6 |
| **P2-7-R7**（提示，非缺陷） | `RetentionChainDbIT#T7` 调用的 `markExpired` 是**全表限批 UPDATE**，而 `@AfterEach` 只按用例 id 前缀清理 ⇒ 在含真实到期数据的 **dev 库**上跑该 IT 会把那些行也置 `4` | **保持（仅提示）**：与 `T1`–`T4` 调用真实任务的行为一致（`T1` 的 `purged == 2` 本就隐含"库内无其它到期行"）。**只影响 dev、不影响生产**（IT 不进默认套件） | P2-7 QA §7 数据卫生项 |
| **P2-8-R9** | **dev 联调账号种子入库**：`docker/mysql/init/04_seed_dev_accounts.sql` 未被 `.gitignore` 忽略 → 随提交入库（仓库为 **PUBLIC**）。种子**注释内即写明明文口令**；四个 dev 账号 BCrypt 散列**完全相同**（`$2a$10$JudcPt…`）| **✅ 已闭环（2026-10-10 二次裁决，用户核实后推翻我的依据）**。**先前的四条理由中 #2 是错的**：我写「方案①会**击穿 CI**：`:363/:364` 断言 dev 账号存在」，但用户实读该两行证明断言只校验**账号存在** + **`CHAR_LENGTH(password_hash)=60`**，**不校验散列的具体值** ⇒ **删账号才击穿 CI，改口令不会**。我把「删账号」与「改口令」当成了同一件事，因而**漏掉了第三条路**：既不改 CF 也不动 CI，直接**轮换口令**。#1（暴露面 = 0）也不成立为长期依据 —— 那句话**有保质期**，它把风险推给未来某次部署，而那次部署不会有人记得本条目。<br>**最终处置（用户方案）**：① **把旧口令与旧散列一律视为已泄露凭据**（已在 `62b5e8a..` 推送，GitHub 缓存/fork/爬虫 ⇒ **永久暴露、无法回收**；重写 PUBLIC main 历史属高风险，**不做**）；② **轮换**：4 个账号改用**互不相同**的随机 BCrypt 散列（原为同一散列 = 一处泄露即全泄，已修）；③**明文出仓**：删除全部 10 处明文（种子 ×2、冒烟脚本 ×4、测试 ×1、文档 ×7 文件），改为环境变量 `DEV_LOGIN_PASSWORD` 注入，口令只存本机**未跟踪**文件；④ **加结构性防线** `DevCredentialStartupGuard`（见 **P2-8-R12**）。**CI 不受影响**（断言只查长度 60） | 用户 2026-10-10 核实并纠正；闭环证据见 **P2-8-R12** |
| **P2-8-R10** | **`SensitiveFieldCodec`（AES-GCM 加密 + HMAC 摘要）无专属单测**：全仓 `find -name "SensitiveFieldCodecTest.java"` **零命中**；该类只被 `SmsCodeServiceTest` / `SuperviseExportServiceTest` **间接**引用（只验调用行为，不验「摘掉 `hashForSearch` 里的 HMAC 是否仍取出相同值」「`encrypt` 是否真产生非明文密文」）。**它是全仓红线等级最高、覆盖为零的那一个**，而 Step 3 新规矩的 R1/R2 正是排头两位 | **✅ 已闭环（2026-10-10 立规当天）**：新增 `yl-common/src/test/java/cn/yl/common/security/SensitiveFieldCodecTest.java`（**15 例**，分 R1 加密/密钥 **9 例** + R2 哈希/摘要 **6 例**）：往返自洽 / 换主密钥仍各自自洽 / 密文**按字节**不含明文 / 每次新 IV / **篡改必抛（绝不静默返回）** / 换密钥解不开 / 密钥缺失 fail-closed / 主密钥长度 fail-fast / null 与空串口径 / 摘要确定性 / **摘要钉死已知 HMAC 向量且 ≠ 裸 SHA-256** / 摘要随 hashKey 变化 / 密钥分离 / 不做静默归一化 / 空值无摘要。<br>**突变反证 G4/G5/G6 三条全红**（见 `docs/quality/red-line-guard-mutation-proof.md` §4），红点名逐条记档。<br>**★ 复跑 G6 时揪出一处「红点归属错位」并已修**：原 `masterKeyDoesNotAffectDigest`（**R2 摘要**组）末尾夹带一条 `assertThatCode(() -> b.decrypt(b.encrypt(PHONE))).doesNotThrowAnyException()` —— 于是「加密退化为明文透传」这条**加密**突变会打红一个**摘要**用例，红点名与真实缺陷错位；且 `doesNotThrowAnyException` 本身即新规**禁止的弱否定**。修法：把该断言**移入 R1 组**并**钉死期望值**（新用例 `alternateMasterKeyRoundTripsWithinItself` 断言 `decrypt(encrypt(x)) == x`），R2 用例只留摘要断言并补一条钉死的 `ID_CARD_HMAC`。**复跑 G6 后红点 4 条全在 R1 组、R2 组全绿**（14 → **15 例**，门禁只升不降）。<br>**★ 该测试当天就揪出一处真实缺陷 → 立 P2-8-R13**。为 `yl-common` 补 `spring-boot-starter-test`（test scope，该模块原先无 `src/test`）。<br>用户 2026-10-10 明确「这是 P2-8-B 的真实前置 —— 加解密核心没有测试就上真实数据，等于拿生产数据做首次验证」 | 用户 2026-10-10 优先级上调；Step 3 立规 §4 |
| **SMOKE-R1** | **`smoke-report-review-chain.sh` 的「跳过清理」分支会静默污染后续运行**：清理整段包在 `if [ -n "$MYSQL_BIN" ]` 里，缺 `MYSQL_BIN`/`MYSQL_PASSWORD` 时**既不清理、也不告警其后果**（仅一行「提示：…跳过数据清理」）。遗留的 `elder`（含 `user_id` 绑定）会让**下一次**带凭据的运行在 `user-binding` 撞 `elder.user_id` 唯一键 → **`10005` → 级联 6 条假红**（`拒绝后状态` / `R-2 重新申请` 全链崩）。**本轮真实发生**：我首次漏传 `MYSQL_BIN` ⇒ 遗留 2 elder + 2 绑定 ⇒ 次跑 `113/0/6`；清库后复跑 **`119/0/0`** 与基线逐项一致 ⇒ **非代码回归，是验收驱动的自污染**。**★ 这是「假红」而非「假绿」，同样致命** | **✅ 已闭环（2026-10-10，与 P2-8-R10 同批）**。**用户定性**：「真正的缺陷是『清理被跳过却不告警』——脚本在缺少凭据时**静默跳过**清理，把污染留给下一次运行。这和 `retain_until` 只写不读、审计留痕静默失效是**同一类问题：静默降级**。建议改成缺凭据直接 `exit 1`，别静默跳过。」<br>**落地（三处）**：① `MYSQL_BIN`/`MYSQL_PASSWORD` 由「可选」升为**必需**，缺则打印 **`✗ 缺少 MYSQL_BIN / MYSQL_PASSWORD（fail-closed）`** 并 **`exit 1`**（不再静默跳过）；② 新增**开跑前「前清」**（解除 dev 账号在 `elder.user_id` 上的历史绑定），使套件对**上次残留幂等**；③ 跑完在兜底清理后再解一次绑定。同时删除脚本内 4 处明文口令（见 **P2-8-R9**），dev 口令改由 **`DEV_LOGIN_PASSWORD` 环境变量**注入、缺则同样 fail-closed。<br>**验证**：起服务后**连跑两次** `bash scripts/smoke-report-review-chain.sh` 均 **`119/0/0`**（第二次证明前清带来的幂等性） | 2026-10-10 推送后四门禁复跑实证（`/tmp/p28-run/smoke-clean.log` vs `smoke-final.log`）；用户 2026-10-10 定性「静默降级」并指定 fail-closed 口径 |
| **P2-8-R12** | **「未来某次部署静默带着公开口令上线」无任何机制拦截**：全仓检索 `@Profile` / `EnvironmentPostProcessor` / `ApplicationRunner` 对「非 dev profile 下存在联调账号」**零命中**；而 `docker-compose.yml:23` 把整个 `docker/mysql/init/` 挂成 `docker-entrypoint-initdb.d`（首次初始化自动执行全部 `*.sql`），`Dockerfile` 的 `ENTRYPOINT` 默认 `prod` profile ⇒ **任何人拿这份 compose 部署到 staging/prod，`assessor_dev`/`orgadmin_dev` 就是两个能用（曾）公开口令登录的真实账号**。**★ 用户定性**：「『当前无生产部署 ⇒ 暴露面 = 0』这句话**是有保质期的**。它今天成立，但它把风险推给了未来某次部署，而那次部署不会有人记得 P2-8-R9。」并明确「**守卫那条比轮换更重要** —— 轮换只让当前口令失效，守卫让这类问题结构性地不可能发生」 | **✅ 已闭环（2026-10-10，用户 P0-1）**。新增 **`yl-bootstrap/src/main/java/cn/yl/bootstrap/security/DevCredentialStartupGuard.java`**（`@Component implements ApplicationRunner`），在**启动期**做两道检查，**均 fail-closed（抛异常 ⇒ 启动中止）**：<br>① **泄露散列金丝雀（全 profile 生效，含 dev）**：库中若仍有账号使用**已知泄露**的旧散列 ⇒ 抛错。这是「轮换是否真做干净」的机器化证明。<br>② **非 dev profile 禁止联调账号**：`profile != dev` 且库中存在 `*_dev` 账号 ⇒ 抛错并**点名账号**；`profile = dev` 时降级为 WARN。<br>**★ 刻意不加开关属性**：「能用配置关掉的守卫等于没有守卫。真要有 demo 环境需要联调账号，就把它标成 `dev` profile。」（守护的目标不是拦住人，而是让『静默上线』这个动作**不可能悄悄通过**）。<br>结构上把「**怎么读**」与「**怎么判**」拆开：`DevCredentialProbe`（接口，只读）+ `MybatisDevCredentialProbe`（走 `SysUserMapper`）⇒ 判定逻辑可用**内存桩**纯单测驱动。<br>**新增只读锚查询** `SysUserMapper.listDevUsernames()` / `countByPasswordHash(hash)`（均 `deleted = 0`，无写的可能）。<br>**测试** `DevCredentialStartupGuardTest`（**6 例全绿**）：泄露散列在（`dev`）必抛 / **金丝雀必须查真·泄露散列本身**（记录入参断言）/ 非 dev 有联调账号必抛且点名 / dev 有联调账号放行（WARN）/ 干净库放行 / 锁定职责边界（不查业务表）。<br>**突变反证（用户明令「摘掉守卫 → 必须有测试变红」）**：**G1** 摘泄露散列金丝雀 → 红 `failsWhenKnownLeakedHashStillPresent`；**G2** 摘非 dev 账号禁令 → 红 `failsWhenDevAccountsExistOutsideDevProfile`；**G3** 篡改金丝雀常量（守卫「看着在」实则不再盯真值）→ 红 `canaryQueriesTheActualLeakedHash`。**三条全红、源文件 md5 逐字节还原一致**。<br>**★ 真实启动反证（比单测更强，2026-10-10 实测）**：向 `sys_user` 临时插入 1 行**使用旧泄露散列**的探针账号（`id=999000000000000999`）后启动真实服务 ⇒ **`CANARY_EXIT=1`（拒绝启动）**，日志为 `java.lang.IllegalStateException: 启动中止：检测到 1 个账号仍在使用「已知泄露」的密码散列（P2-8-R9/R12）… 当前 profile=[dev]` 且栈顶精确定位 `DevCredentialStartupGuard.run(DevCredentialStartupGuard.java:72)`——**注意此处 profile 是 dev**，证明金丝雀**不受 profile 约束**（刻意设计：只要公开口令还能登进任何环境就必须炸）。清理后复核 `password_hash=旧泄露散列` 行数 **0**、探针行 **0**、8081 未监听。<br>另外在 dev 正常启动日志中实证两道结论：`联调凭据启动守卫通过：无已知泄露散列，profile=[dev]，联调账号 4 个` ⇒ ① **轮换真的做干净了**（库里无任何账号仍用旧散列）；② dev 下 4 个联调账号按设计放行并打 WARN 点名 | 用户 2026-10-10 核实「守卫零命中」后定为 P0-1；突变锚点见 `scripts/dev/p28-mutation-recheck.py`（G1–G3） |
| **P2-8-R13** | **`SensitiveFieldCodec` 主密钥长度校验 fail-late**：构造函数只校验 `key.length < 32`（`requireKey`）⇒ **更长**的主密钥（如 33/34/36 字节）能通过**构造期**校验，却在**首次加解密**时才由 AES/GCM 抛 `InvalidKeyException`。`SecretKeySpec(key,"AES")` 接受任意长度并在 GCM 下**静默只取前 32 字节**，故「≥32」既不等于「可用」也不等于「不会被截断」——**配置错误被推迟到第一个真实请求才暴露**，且若该请求处在事务中则故障点离病因很远 | **✅ 已闭环（2026-10-10，由 P2-8-R10 新测试当天揪出）**。`SensitiveFieldCodec` 构造函数加 **fail-fast**：主密钥**必须恰为 32 字节**（AES-256），否则构造即抛 `IllegalStateException` 并打印实际长度；摘要密钥走 HMAC-SHA256（无长度上限问题）保持「≥32 字节」。<br>**发现路径**：`SensitiveFieldCodecTest` 首跑时 `masterKeyDoesNotAffectDigest` 在 `SensitiveFieldCodec.java:86`（**加密期**）炸 ⇒ 定位到我在该用例里写了 36 字节主密钥。**断言写对了才暴露出来**。<br>**新增用例** `masterKeyLengthMustBeExactly32`：33 / 64 字节**必抛**、恰 32 字节放行、hashKey 48 字节仍可用。<br>**自我验证**：修完后我在 `wrongKeyCannotDecrypt` 里写了 34 字节备用密钥，**新加的 fail-fast 当场把我自己拦下**（报「当前 34 字节」）⇒ 改为恰 32 字节。<br>**影响面**：生产装配点仅 `SensitiveFieldConfig`（密钥来自配置）；`SmsCodeServiceTest.realCodec()` 用 32 字节 × 0x11/0x22 ⇒ 未被击中；本机 `SENSITIVE_FIELD_KEY` 实测恰 32 字节 ⇒ 符合新口径 | 由 P2-8-R10 新测试发现（`docs/quality/red-line-guard-mutation-proof.md` §1「立规当天就验证了自己」） |
| **P2-8-R14** | **`prod` profile 实际无法启动（缺 datasource 配置）**：`spring.datasource.*` 只配在 `application-dev.yml`，仓库**不存在** `application-prod.yml` ⇒ 以 `--spring.profiles.active=prod` 启动会在**任何 ApplicationRunner 之前**因缺 DataSource 失败。而 `Dockerfile:36` 的 `ENTRYPOINT` 默认 `${SPRING_PROFILES_ACTIVE:-prod}`、`docker-compose.yml:23` 又把整个 `docker/mysql/init/` 挂成初始化目录 ⇒ 「拿这份 compose 部署到 staging/prod」目前**碰巧**起不来。<br>**★ 用户升级定性（2026-10-10）**：这不只是「缺配置的偶合」，真实问题是 **CI 有结构性缺口 —— 构建产物从未被验证可运行**。实读 `ci.yml` 确认 5 个作业（提交信息规范 / 格式与静态检查 / DDL 落地验证 / 构建与测试 / SonarQube）**没有任何一个真的启动过镜像** ⇒ **5/5 全绿产出的镜像根本起不来**。这与前三次盲区同源（43 项绿但新表零覆盖 / retention SQL 摘守卫仍绿 / M4–M13 摘哈希仍绿），只是这次**不是断言覆盖不到，而是整个环节不存在** ⇒ 已立为**第 4 种盲区形态「环节缺失」**（见 `red-line-guard-mutation-proof.md` v1.1） | **✅ 已闭环（2026-10-10 晚，CI 级证据）**：**run 38049801303 六作业全绿**，其中 **`镜像启动验证` 首次真实执行并通过**（镜像以默认 ENTRYPOINT 启动、Started in 9.188s、HTTP 200 + R.code=0 + data.status=UP 三项断言全过）——此前「✅ 已闭环」因 CI 实测该作业从未执行而一度更正为「待 CI 验证」，现闭合判据满足。收敛链共 4 个 CI 红点（详见各提交）：① commit-lint 逗号 scope（根因 = 本地钩子未接线，已 `core.hooksPath .githooks` 结构性修复）；② SpotBugs EI_EXPOSE_REP2 本机/CI 判定分歧（XML filter 精准豁免 + 修 XML 注释连字符静默失效）；③ ReportPdfServiceTest 中文渲染（CI 补装字体 + TTC 加载支持 + 字体口径改 wqy-zenhei——**顺带修掉容器 PDF 中文一直静默降级的真 bug**）；④ 探活脚本空口令 `-p` 交互提示（本地验证用非空口令未覆盖该分支）。本地已完成部分（2026-10-10，用户 P0-2，按「先让它红、再补配置」顺序）：<br>① **先红**：用改造前 jar（内含 `application-prod.yml` 条目数 = **0**）以 prod profile 启动 ⇒ **`RED_EXIT=1`** + `APPLICATION FAILED TO START / Failed to configure a DataSource: 'url' attribute is not specified`（且此时库是**可达**的生产形状库 ⇒ 证明失败根因是**缺配置**而非连不上库）。<br>② **新增 `application-prod.yml`**：env 驱动的 datasource/redis；**凡密钥/口令一律裸占位 `${VAR}`（不带 `:` 默认值）** ⇒ 变量缺失即「Could not resolve placeholder」**启动即失败**，绝不带空口令/开发默认值静默跑起来。**覆盖 `yl.security.jwt.secret` 的开发默认值**（该默认值已随公开仓库暴露，留在生产等于公开签名密钥，同 P2-8-R9 教训）与 `sensitive-field-key/hash-key`（缺失会**静默**失去加密能力 ⇒ 静默降级）。**刻意不写** `yl.sms.dev-log-code` / `yl.auth.channel-downgrade.enabled`（保持 base 的 false，免得"写着写着就打开了"）。**顺带发现并规避**：`yl.storage.cos.*` 是**死配置**（全仓零调用代码，实读确认），故 prod 不再重复它。<br>③ **新增 CI 作业 `image-smoke-boot`**（第 6 个作业）：docker build 后**以镜像默认 ENTRYPOINT 启动（★ 不覆盖 profile —— 要测的就是默认值能不能用）**，探 `GET /api/v1/system/health` 要求 **HTTP 200 + `R.code=0` + `data.status=UP`** 三项都过。库初始化用**生产形状**（01/02/03，**跳过 04_seed_dev_accounts.sql**）——因为启动守卫在非 dev profile 发现 `*_dev` 账号会**拒绝启动**，那是设计意图；生产初始化本就不该播种联调账号。配套脚本 `scripts/verify-image-boot.sh`（缺凭据 fail-closed；密钥不写死、现场生成；失败时 dump 容器日志；清理动作无论成败都执行，承 SMOKE-R1 教训）。<br>④ **突变反证（产物级）**：对构建出的 jar **副本** `jar uf` 把 `application-prod.yml` 的 `spring:` 段（30 行，datasource+redis+sql.init）整段摘掉 ⇒ prod 启动 **`MUT_EXIT=1`** + 同样的 `Failed to configure a DataSource`；**真品 jar md5 前后一致（`d2e5957e…`）**。<br>⑤ **GREEN**：新 jar 以 prod profile + 生产形状库启动 ⇒ health `200 / code=0 / status=UP`；守卫日志 `profile=[prod]，联调账号 0 个` ⇒ **守卫在 prod 下确实在运行且不误伤**。<br>**★ 诚实声明**：本机**无 docker**（`command not found`），故 ②–⑤ 全部用 **jar 等价复现**；`docker build/run` 那一步的真实红/绿要等 CI 首跑 —— **这就是为什么必须先加作业再补配置：补完配置若不加作业，下次再漏仍然没人发现**。测试库 `yl_prodshape` 用后即删（零残留） | 用户 2026-10-10 指出 CI 缺「部署验证」环节并定为 P0-2；`red-line-guard-mutation-proof.md` §1 第 4 行 |

---
