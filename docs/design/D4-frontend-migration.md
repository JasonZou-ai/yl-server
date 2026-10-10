# D-4b 端侧切真专章 · 家属端（uni-app 三端）

> 角色：架构师（高见远）｜配套：`docs/design/D4-family-domain-design.md`（后端主体设计）
> 范围：`<APP>` = `C:/Users/Administrator/WorkBuddy/2026-09-08-13-00-34/stage3_开发`
> 落点：**weixin-app 改一份 → md5 同步 douyin-app / ios-app**（`weapp` **不在改列**）
> 真源：`weixin-app/src/utils/{family.ts,real-api.ts,api.ts,request.ts}`、`pages/family-*/`（实读）

---

## 0. 现状（实读）

| 项 | 现状 | 位置 |
|---|---|---|
| 数据层入口 | 页面一律 `import ... from '../../utils/api'`；`api.ts` = `export * from './real-api'`（**单行切换**） | `utils/api.ts` L12 |
| 家属段现状 | `real-api.ts` 的"缺口 C（家属端 F3）"**全部显式委托 `family.ts` Mock**（`getMyBindings`/`requestBinding`/`getReports`/`getReport`/`getTrend`/`getCarePlan`） | `utils/real-api.ts` L310-359 |
| Mock 领域层 | `family.ts`：`listBindings`/`visibleElders`/`getBinding`/`pendingBindings`/`submitBindApply`/`advanceBindingDemo`/`revokeBinding`/`listReports`/`getReport`/`listTrend`/`familyCareAdvice`/`getMsgPrefs`/`getPrivacyPrefs`/`listAuthLog` | `utils/family.ts` 全文 |
| 三条越权防线（Mock 内） | ① `visibleElders()` 只返回 `ACTIVE`；② 所有报告/趋势/照护带 `relationId` 校验；③ `expiresOn < 今天 → EXPIRED` | `family.ts` L11-15 / L141-149 / L288 |
| 雪花精度工具 | `requestWithText()` 返回原始响应文本；`bigIdFromText(text, field)` 正则取长整型串 | `utils/request.ts` L178-189；`real-api.ts` L56-59 |
| 家属页（**实读 8 个**） | `family-home` / `family-bind` / `family-binds` / `family-reports` / `family-trend` / `family-care` / `family-privacy` / `family-messages` | `ls weixin-app/src/pages/` |
| 三端 md5（实读，需与目标比对） | `family.ts 6813264caa263d42516deba53f80ba4a` / `real-api.ts f41fd233e510f9cd38a423b024c8fddb` / `api.ts 2a06d09ac6693e97d23248ff4ff61e78` / `request.ts eae7df44d986f83a6f39edc7902bc031` | `md5sum` 实测 |
| **★ 三端 md5 终态（D-4b 收口，2026-09-30 实读；三端逐一 SAME）** | `family.ts eaa20cd2d87d40b962e7973d91890c2c` / `real-api.ts 27b6649ab07c9c4f51411782ab928838` / `request.ts a068967aa658c78c2329aca623d60481`（**已改**，见 §2.7 偏离①）/ `api.ts 2a06d09ac6693e97d23248ff4ff61e78`（未改）/ `family-bind.vue 1f34acf504908f2fd73d1a46e138821b` / `family-binds.vue 1305c5fb0391355514c7ef7bdb3ac9cd` / `report.vue 4e5399d20f14f07e8293d2d5a90effaa`（**已改**，见 §2.7 偏离②） | `md5sum` 实测 |

> **口径差异说明**：团队负责人口径为"7 个家属页"，**实读为 8 个**（多 `family-messages`）。本专章按**实读 8 个**列改动与否。

---

## 1. 目标

1. `real-api.ts` 的家属段由"委托 Mock"→**真实调用**后端 9 端点（`/family/bindings/**`），保持**签名对齐 Mock**（页面零 API 改动）。
2. **三条越权防线迁移到适配层**：`relationId` 校验、仅 `ACTIVE` 可见、到期由**后端 `status`** 决定。
3. **字段不齐一律显式降级**（`name`/`gender`/`age`/`boundAt`/`note` 缺失即空态），**绝不造数**。
4. `requestWithText()` 覆盖所有以 `relationId`/`reportId` 为 path 参数的调用（雪花精度）。
5. 改 **weixin-app** → md5 同步 **douyin-app / ios-app**。

---

## 2. 逐文件 diff 要点

### 2.1 `utils/family.ts`（改）

| 改动 | 说明 |
|---|---|
| **保留** | 全部 `interface`/`type`（`Relation`/`BindStatus`/`BoundElder`/`FamilyReport`/`TrendPoint`/`CareAdvice`/`BindApplyInput`）、`BIND_STATUS_TEXT`/`BIND_STATUS_CLS`/`RELATION_OPTIONS`/`TERM_OPTIONS`/`levelTone`/`shownNameOf`、消息/隐私偏好（`getMsgPrefs`/`getPrivacyPrefs`/`setMsgPrefs`/`setPrivacyPrefs`） |
| **删除** | `listBindings` 的**本地到期判定段**（`if (b.status === 'ACTIVE' && b.expiresOn !== 'LONG_TERM' && b.expiresOn < t) → EXPIRED`）—— 到期由**后端 `status`** 决定（避免端侧自造判定口径） |
| **删除（仅在生产路径）** | `advanceBindingDemo`/`rememberDemoKey`/`DEMO_APPROVED`/`DEMO_REPORTS`/`ORG_ELDERS`（内测演示数据）**不再被生产页面引用**；`submitBindApply`/`listReports`/`listTrend`/`familyCareAdvice` 的 Mock 实现保留但**不再被 `real-api.ts` 调用**（改由真实调用替代），可在注释标记 `@deprecated 仅供离线演示` |
| **新增** | `RELATION_LABEL: Record<string, Relation>`：`{ CHILD:'子女', SPOUSE:'配偶', KIN:'亲属', GUARDIAN:'监护人' }`（后端枚举 → 端侧展示） |
| **`shownNameOf` 语义调整** | 后端 `elderName` 恒为**脱敏**（本卡不新增全名通道）→ `ACTIVE` 时 `b.name` 为空串 → 自然回退到 `b.maskedName`；**界面文案**由"授权生效后将显示老人完整姓名"改为"显示脱敏姓名"（`family-bind.vue` L222 等） |

### 2.2 `utils/real-api.ts`（改，**家属段核心**）

```ts
/* ============ 家属段：真实调用（D-4b 切真） ============ */
import { request, requestWithText } from './request'
import type { BindStatus, BoundElder, BindApplyInput, FamilyReport, TrendPoint, CareAdvice } from './family'

const FAMILY_PATH = '/family'

/** ★ 状态码 ↔ BindStatus 显式映射（唯一约定，勿各写一份；未知值→EXPIRED 最保守） */
export function bindStatusOf(status: number): BindStatus {
  switch (status) {
    case 0: return 'PENDING_ELDER'
    case 1: return 'PENDING_ORG'
    case 2: return 'ACTIVE'
    case 3: return 'REJECTED'
    case 4: return 'EXPIRED'
    default: return 'EXPIRED'
  }
}

/** 后端 FamilyBindingView → 端侧 BoundElder（缺失字段显式降级，绝不造数） */
function toBoundElder(v: Record<string, unknown>, relationId: string, elderId: string): BoundElder {
  const authEndAt = v.authEndAt ? String(v.authEndAt) : ''
  return {
    relationId,                                   // ★ 由 requestWithText 原文取，非 v.id
    elderId,                                      // ★ 同上
    name: '',                                     // 契约无全名 → 恒空（不造数）
    maskedName: v.elderName ? String(v.elderName) : '',
    relation: RELATION_LABEL[String(v.relation || '')] ?? '亲属',
    gender: '',                                   // 契约无 → 空态
    age: 0,                                        // 契约无 → 空态
    status: bindStatusOf(Number(v.status)),
    expiresOn: authEndAt ? authEndAt.slice(0, 10) : 'LONG_TERM',
    boundAt: v.authStartAt ? String(v.authStartAt) : '',   // 契约无 boundAt → authStartAt ?? ''
    note: v.rejectReason ? String(v.rejectReason) : '',
  }
}

/** 我的绑定列表（★ 走 requestWithText 保 snowflake 精度；只取 PageResult.list） */
export async function getMyBindings(): Promise<BoundElder[]> {
  const { data, text } = await requestWithText<{ list?: Record<string, unknown>[] }>({
    url: `${FAMILY_PATH}/bindings`, method: 'GET', data: { pageNum: 1, pageSize: 50 },
  })
  const list = Array.isArray(data.list) ? data.list : []
  // 逐行从原文提取 id / elderId（JSON.parse 已截断末位）
  return list.map((v) => {
    const marker = `"elderId":`;
    const rid = firstIdFor(text, v, 'id')      // 见 §2.2 注：按行定位
    const eid = firstIdFor(text, v, 'elderId')
    return toBoundElder(v, rid, eid)
  })
}
```
> **注（雪花精度实现要点）**：`requestWithText` 只能取"**首个**同名字段"的原文串（`bigIdFromText` 用 `new RegExp('"'+field+'"...')` 取首个匹配）。列表内多行都有 `id`/`elderId` → 需**按行切分原文**再逐行取；实现建议：在 `request.ts` 增 `bigIdsFromText(text, field): string[]`（取全部匹配，按出现顺序），与 `list` 同序对齐；若实现者选择"保留 Jackson 侧以字符串返回 `id`"，则**必须同时**改后端序列化口径 → **不在本卡范围**（登记于 §6 待明确）。**验收以"越权用例不误判"为准**（见 §5 H5 清单）。

| 函数 | 真实调用 | 关键约束 |
|---|---|---|
| `getMyBindings()` | `GET /family/bindings` | ① 取 `list`；② `relationId`=原文 `id`；③ 降级映射 |
| `requestBinding(input)` | `POST /family/bindings`（`Idempotency-Key` 必带） | 入参：`{ elderId: Number(input.elderId), relation: REVERSE_RELATION[input.relation], verifyMethod: 'SCAN', elderName, idCardNo }`（`elderName`/`idCardNo` 见 §3 `family-bind.vue`） |
| `getReports(relationId)` | `GET /family/bindings/{relationId}/reports` | ★ path 用**原文** `relationId`；**进调用前先 `getMyBindings()` 校验该 relation 存在且 `ACTIVE`**（防线②） |
| `getReport(relationId, reportId)` | `GET /family/bindings/{relationId}/reports/{reportId}` | ★ 二者均走原文；先 `getReports` 校验 reportId 归属 |
| `getTrend(relationId)` | `GET /family/bindings/{relationId}/trend` | 同上 |
| `getCarePlan(relationId)` | `GET /family/bindings/{relationId}/care-plan` | `items` 直接取；`services` 仍取本地静态目录（后端无对应字段 → **保留既有 `SERVICES` 常量**） |
| `getMessages()` | **显式返回 `[]`**（不变） | 后端无消息域端点 |

**适配层必保留的越权防线（对齐 `family.ts` 三条硬规则）**
1. `getReports`/`getTrend`/`getCarePlan`/`getReport` 进入调用前，**先经 `getMyBindings()` 校验 `relationId` ∈ 本人 `ACTIVE` 绑定**；不满足 → 返回空（**不发起请求，不返回全量**）。
2. `relationId` 为空 → 返回空（**不发起"传空即全量"的调用**）。
3. 到期：不再本地判定；`status` 由后端给。

### 2.3 `pages/family-bind/family-bind.vue`（改，**入口收敛**）

| 改动 | 说明 |
|---|---|
| **移除**"手机号搜索" tab | `via` 恒 `'qrcode'`；`ORG_ELDERS`/`doSearch`/`phoneOk`/`searchPhone`/`match`/`maskedName`（本地计算）**删除**；"手机号搜索"入口改为**禁用占位**（"敬请期待"，见 §1.5 裁决） |
| **扫码内容 → `elderId`** | `onScan()` 成功回调内**解析 `elderId`**：契约约定格式 `YL-FAMILY:<elderId>`（19 位数字串）→ 正则 `/YL-FAMILY:(\d+)/` 提取；兼容"纯数字串"。**提取值以字符串保存**（雪花精度），提交时 `Number(...)` 入 body |
| **新增核验字段** | 表单增"老人姓名"+ "老人身份证号"两个 `input`（核验前置，§1.1）；`canSubmit` 增 `!!elderName && !!idCardNo` |
| **`targetMasked` 调整** | 申请阶段后端不下发 `elderName` → `maskedName` 为空；展示"待确认老人"（**不本地造脱敏名**） |
| **提交入参** | `requestBinding({ elderId, relation, verifyMethod:'SCAN', elderName, idCardNo, proofCosKey? })`；`Idempotency-Key` 由 `real-api.ts` 生成（`crypto.randomUUID()` 或时间戳+随机） |
| **内测推进块** | `advanceBindingDemo`/`rememberDemoKey` 相关 UI（"内测验收：推进流程"）**移除**（正式流程由老人端/机构后台触发） |
| **文案** | "授权生效后将显示老人完整姓名" → "授权生效后可查看该老人的评估报告与照护建议" |

### 2.4 `pages/family-binds/family-binds.vue`（改，**解绑真实化**）

| 改动 | 说明 |
|---|---|
| **数据源** | `listBindings()` → `await getMyBindings()`（`onShow` 改异步）；`rows` 里 `expiresOn`/`boundAt`/`note` 走降级值 |
| **解绑（④闸）** | `onRevoke(b)`：① `POST /auth/second-verify`（`method:'PHONE'`，`credential` 取自页面输入/配置）换 `X-Second-Verify-Token`；② `POST /family/bindings/{relationId}/unbind`（`Idempotency-Key`）；缺凭据 → 后端 `10008` → 提示"需先完成二次验证" |
| **移除** | `advanceBindingDemo` 的"内测：推进状态"链接（正式流程由老人端/机构触发） |
| **保留** | 到期提醒 `daysLeft`（基于 `expiresOn`，仅在 `ACTIVE` 时计算）；`revokeBinding` 本地删除**改为**调用真实解绑后 `refresh()` |

### 2.5 读页面（`family-home` / `family-reports` / `family-trend` / `family-care`）（改）

| 页面 | 改动要点 |
|---|---|
| `family-home.vue` | `import { visibleElders, pendingBindings, listReports, … } from '../../utils/family'` → **改从 `'../../utils/api'`** 取 `getMyBindings`/`getReports`；`cards` 的"最近一次报告"改为异步聚合（`getReports(e.relationId)[0]`）；`BIND_STATUS_TEXT/CLS`/`shownNameOf`/`levelTone` 仍从 `family.ts`（纯展示常量，可保留 import） |
| `family-reports.vue` | `listReports(relationId)` → `await getReports(relationId)`；`FamilyReport` 字段映射（后端 `reportId`/`reportNo`/`gradeCode`/`totalScore`/`publishedAt` → 端侧 `assessDate` 用 `publishedAt.slice(0,10)`） |
| `family-trend.vue` | `listTrend(relationId)` → `await getTrend(relationId)`；~~`dims` 由后端 `dimensionScores` 映射~~ **★ 本假设不成立（2026-09-30 实读）**：后端 `FamilyTrendPointView` 仅含 `reportId/publishedAt/gradeCode/levelName/totalScore`，**无 `dimensionScores`** ⇒ 趋势页维度分**恒空态**（**不造比例还原**）；维度分只有「报告详情」端点可得。登记残留 **D4-R11** |
| `family-care.vue` | `familyCareAdvice(relationId)` → `await getCarePlan(relationId)`；`services` 仍取本地 `SERVICES` |
| **共用** | 均**保留**"先校验 `relationId` ∈ 本人 `ACTIVE`"（在适配层已内置）；`relationId` 从页面参数传入时须为**字符串原文**（`family-home` 跳转 `?relationId=${e.relationId}` 已是字符串） |

### 2.6 `family-privacy.vue` / `family-messages.vue`（**不改**）

- 无对应后端端点（隐私偏好/消息均本地存储）。**保持现状**，登记残留（后端无消息域/隐私偏好端点）。

### 2.7 ★ 实施期两处**获批偏离**（2026-09-30，工程师提出 → 我裁定）

> 两处均**未静默改设计**，已在 `_验收证据/D4b-frontend-implementation-log.md` 说明理由；此处为设计侧回填。

| # | 偏离 | 理由（为什么必须改） | 裁定 |
|---|---|---|---|
| ① | **`utils/request.ts` 增可选 `header?: Record<string,string>`**（故三端 md5 由基线 `eae7df44…` → `a068967a…`，三端仍两两相等） | 后端写端点**从请求头取值**：`IdempotentAspect` 读 `Idempotency-Key`（缺 → `10007`）、`SecondVerifyGuard` 读 `X-Second-Verify-Token`（缺 → `10008`）。原封装**不支持自定义头** ⇒ **所有写端点物理不可达**（功能阻断，非风格问题） | **批准**。默认空对象 ⇒ 既有行为不变；三端同步（`family.ts`/`real-api.ts`/`request.ts` 三文件仍 md5 一致） |
| ② | **`pages/report/report.vue` 的「家属分支」切真**（§2 清单未列该文件） | H5 主链含「报告详情」，而该页家属分支原 `import … from '../../utils/family'`（**Mock 域层**）→ 绑定切真后详情恒 `familyDenied`（家属永远打不开自己的报告） | **批准**。**仅改 `family === true` 分支**，评估员分支不动（避免波及既有评估链路） |

**性质说明**：① 属"设计漏项"（未预见写端点的请求头依赖），② 属"设计清单漏文件"。两处均**不改契约、不改后端**，爆炸半径限于端侧数据层。

### 2.8 ★ 实施期发现并修复的**真实缺陷**（精度再犯）

`real-api.ts#requestBinding` 原为 `elderId: Number(input.elderId)` —— 19 位雪花经 `Number()` **丢末位**（实测 `…777 → …776`）→ 后端匹配不到老人 → 绑定提交失败。**修复**：改 `String(input.elderId)`（Jackson 宽松把 JSON 字符串转 `Long`，精度无损；与 `submitAssessment` 的 `assessorId` 同口径）。同一坑在 E2E 种子脚本内亦复现（`Number('900000000000000001')` → `…000` → 后端 `20003`）。
> **教训**：**雪花精度是"跨语言/跨层重复犯"的坑**（后端 `Long` → JSON → JS `Number`）⇒ 端侧凡触达 19 位 id，**读写两侧都必须走字符串**（读用 `requestWithText`/`bigIdFromText`，写用 `String(...)`）。

---

## 3. 端侧改造后数据流（示意）

```
family-home.vue ─┐
family-binds.vue ├─> utils/api.ts ──(export *)──> utils/real-api.ts ──> utils/request.ts ──> /api/v1/family/**
family-reports.vue┤                                   │  ├ 降级映射(bindStatusOf/toBoundElder)
family-trend.vue  │                                   │  ├ relationId 归属校验(防线②)
family-care.vue   ┘                                   │  └ 雪花原文(requestWithText)
                                                      └─ 类型/文案 ← utils/family.ts
family-bind.vue ─> submitBindApply(改) ──────────────> POST /family/bindings
```

---

## 4. 三端 md5 同步（可验收步骤）

> 依据：P1-5 已验证路子（`weapp` 非基准；uni-app 三端 `family.ts`/`real-api.ts`/`api.ts`/`request.ts` 本应一致）。

```bash
APP="C:/Users/Administrator/WorkBuddy/2026-09-08-13-00-34/stage3_开发"

# 1) 改 weixin-app 一份（本卡改动集）
#    weixin-app/src/utils/family.ts、weixin-app/src/utils/real-api.ts
#    weixin-app/src/pages/family-{home,bind,binds,reports,trend,care}/*.vue

# 2) 同步 utils 到 douyin-app / ios-app（页面文件按端是否复用同一份决定；utils 三端共用）
for end in douyin-app ios-app; do
  cp "$APP/weixin-app/src/utils/family.ts"     "$APP/$end/src/utils/family.ts"
  cp "$APP/weixin-app/src/utils/real-api.ts"   "$APP/$end/src/utils/real-api.ts"
done

# 3) md5 三端逐一比对（★ family.ts / real-api.ts 两个文件必须三端一致）
for f in family.ts real-api.ts api.ts request.ts; do
  echo "== $f =="
  md5sum "$APP/weixin-app/src/utils/$f" "$APP/douyin-app/src/utils/$f" "$APP/ios-app/src/utils/$f"
done
# 期望：family.ts 三行一致 / real-api.ts 三行一致（api.ts 与 request.ts 本卡不改，应保持三端一致）

# 4) 若页面文件三端各自独立（需核对 douyin-app/ios-app 的 pages/family-* 是否软链/复制）
for end in douyin-app ios-app; do
  diff -q "$APP/weixin-app/src/pages/family-home/family-home.vue" "$APP/$end/src/pages/family-home/family-home.vue"
done
```

**验收判据（机械）**：
- `family.ts` / `real-api.ts` **三端 md5 两两相等**（否则同步未完成）；
- `api.ts` / `request.ts` **三端 md5 不变**（本卡不改这两文件）；
- 页面 diff 无差异（若三端页面本为独立副本，则须逐端同步）。

---

## 5. H5 验收清单（对照 `_验收工具/run-h5-acceptance.sh`）

> 后端须已启动 + 已配 `SENSITIVE_FIELD_KEY`/`SENSITIVE_FIELD_HASH_KEY`（**核验前置**）+ `SECOND_VERIFY_DEV_CODE`（解绑④闸）+ `04_seed_dev_accounts.sql` 已播 `family_dev`。

```bash
bash _验收工具/run-h5-acceptance.sh weixin douyin ios   # 三端顺序复用 5173（CORS 白名单）
```

| # | 场景 | 期望 |
|---|---|---|
| H1 | 账密登录 `family_dev`（渠道降级开关开启） | 进入家属首页；无报错提示 |
| H2 | 首页无绑定 → 空态 | 显示"还没有绑定关系"+ "去绑定老人" |
| H3 | 扫码绑定（内测占位亲情码） | 解析出 `elderId`；填写姓名+身份证后可提交 |
| H4 | 提交申请（未配密钥后端） | 提示业务码 `40001`（**不静默成功**） |
| H5 | 申请列表 | `PENDING_ELDER` 卡片**不含任何老人数据**（`maskedName` 空 → "待确认老人"） |
| H6 | 越权：手工改 `?relationId=<他人/不存在>` | **空态**（适配层防线②拦截，**不发起请求**或后端 `10004`） |
| H7 | 报告列表（已生效绑定） | 仅显示"已发布"报告；`relationId` 为**原文**（越权用例不误判，§2.2 注） |
| H8 | 解绑（无二次验证凭据） | 提示 `10008`「该操作需先完成二次验证」 |
| H9 | 解绑（有凭据） | 解绑成功；列表刷新为 `EXPIRED`/移除 |
| H10 | 登出 | 本地双令牌清除 → 重新登录页；旧令牌不可再用（后端黑名单，H5 不直接验，由冒烟 `[9/9]` 覆盖） |

**证据**：`_验收证据/h5-{weixin,douyin,ios}/`（`run-h5-acceptance.sh` 自动落盘）。

---

## 6. 待明确事项

| # | 事项 | 阻塞度 | 建议处置 |
|---|---|---|---|
| 1 | **列表内多行雪花 id 原文提取**：`bigIdFromText` 只取**首个**同名字段 → 列表需按行取值 | **中** | 实现时在 `request.ts` 增 `bigIdsFromText(text, field): string[]`（全部匹配，按序），与 `list` 同序对齐；**验收以"越权用例不误判"为准** |
| 2 | `requestBinding` 的 `Idempotency-Key` 生成 | 低 | `real-api.ts` 内 `crypto.randomUUID()`（不可用时回退 `'fk-'+Date.now()+'-'+Math.random()）` |
| 3 | 二次验证凭据的页面交互（解绑） | 低 | `family-binds.vue` 弹层采集 `credential`（联调期为配置口令），走 `POST /auth/second-verify` |
| 4 | `family-home` "最近一次报告"异步聚合的加载态 | 低 | 显示骨架/空态；失败不阻塞首页渲染 |
| 5 | 3 个附件/展示接口的字段映射（`assessDate` 来源） | 低 | `publishedAt.slice(0,10)` 作 `assessDate`（**不造数**） |

---

## 附：与后端设计的对应关系

- 端点契约：`D4-family-domain-design.md` §4（11 操作）
- 字段降级清单：`D4-family-domain-design.md` §1.4
- 状态机映射：`D4-family-domain-design.md` §10#3
- 雪花精度边界：`D4-family-domain-design.md` §10#10
