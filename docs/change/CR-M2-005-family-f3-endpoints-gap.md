# CR-M2-005 家属端（F3）接口缺口 —— 端侧已委托 Mock、后端零端点

| 项 | 内容 |
|---|---|
| 编号 | CR-M2-005 |
| 类型 | 变更 / 缺口登记（**顺延项**，非缺陷修复） |
| 优先级 | P2（不阻塞 M2 主线；阻塞 F3 家属端上线） |
| 状态 | **📌 已登记并完成契约先行（2026-09-24），实现顺延至 D-4** |
| 登记日期 | 2026-09-23 ｜ 契约冻结 2026-09-24 |
| 来源 | P1-5「其余端切真实接口」（四端数据层统一切真实） |
| 关联 | CR-M2-002（鉴权收口）、CR-M2-004（dev 渠道降级）、`openapi/yl-api.yaml` v1.1.0、**D-1 裁决 2/3/4/5** |

---

## 〇、契约先行结论（2026-09-24，D-1 契约冻结）

原卡两套路径（本卡端侧期望 `/family/reports`、`/family/trend`、`/family/care-plan` 与 CR-M2-001 反向缺口的
`/family/bind`、`/family/unbind`）**互不一致**。D-1 裁决后**统一为以 `{relationId}` 为轴的资源化路径**并已写入契约 v1.1.0（`x-status: planned`，9 个操作）：

| 函数 | **冻结路径** | 权限码 | 数据域 | 子卡 |
|---|---|---|---|---|
| `getMyBindings()` | `GET /api/v1/family/bindings` | `account:family:bind` | 关系（本表按 `family_user_id`） | D-4 |
| `requestBinding(input)` | `POST /api/v1/family/bindings` | `account:family:bind` | 关系（写入） | D-4 |
| （机构审核） | `POST /api/v1/family/bindings/{id}/approve` | `account:family:bind` | 机构 | D-4 |
| （老人拒绝） | `POST /api/v1/family/bindings/{id}/reject` | `account:family:bind:reject` | 关系 | D-4 |
| （解绑） | `POST /api/v1/family/bindings/{id}/unbind` | `account:family:unbind`（+④二次验证） | 关系 | D-4 |
| `getReports(relationId)` | `GET /api/v1/family/bindings/{relationId}/reports` | **`report:view`（复用）** | 关系 + 仅 `PUBLISHED` | D-4 |
| `getReport(relationId, reportId)` | `GET /api/v1/family/bindings/{relationId}/reports/{reportId}` | **`report:view`（复用）** | 关系 + 仅 `PUBLISHED` | D-4 |
| `getTrend(relationId)` | `GET /api/v1/family/bindings/{relationId}/trend` | **`report:view`（复用）** | 关系 + 仅 `PUBLISHED` | D-4 |
| `getCarePlan(relationId)` | `GET /api/v1/family/bindings/{relationId}/care-plan` | **`report:view`（复用）** | 关系 + 已确认 | D-4 |

**四项裁决（D-1 §七，全部按默认建议通过）**：

1. **权限码零新增**（裁决 3）：家属端读接口复用 `report:view` —— 与 CR-M2-001 对 `elder:archive:read` 的裁决同构
   （"权限点全角色授予、可见范围由第三闸控制"）。故本卡原"7 个方法"对应 **9 个操作**（新增 3 个绑定动作 + 1 个机构审核）。
2. **`getMessages()` 移出 D-4**（裁决 4）：消息中心属 PRD §3 功能点 21/22，已由 CR-M2-001 裁决「本期不含·V2」；
   端侧现"显式返回空数组、不造数"的处理**维持不变**，契约**不登记** `/family/messages`。
3. **数据域前置已解除**（裁决 2）：家属由 `data_scope=1` 改为 **5-关系级**，第三闸按 `elder_family_bind` 生效绑定收口
   —— 此前"接口写完也查不到数据"的阻断面已消除（见 D-1 §11.3）。
4. **老人侧仅"拒绝"**（裁决 5）：`account:family:bind:reject`，放行由机构审核承载（"默认通过 + 可拒绝"）。

> **对端侧的约束（D-4 实现时必须遵守）**：`relationId` 是**绑定关系 id**（不是 `elderId`）；
> 越权用例（家属拼他人 `relationId` / `reportId`）**必须在数据域层被拒**，不得仅靠服务层校验。
> 本卡的机构侧 3 方法（`mockElders()` / `mockHomeStats()` / `mockCarePlan(name)`）分别归 **D-2**（`elder` 域）
> 与 **D-3**（统计/报表域），不在 D-4 范围。

---

## 一、现象

四端数据层（`utils/api.ts` → `real-api.ts`）切到真实接口后，**家属端 7 个方法无法接真实后端**：

| 端侧函数（`real-api.ts`） | 期望后端端点 | 现状 |
|---|---|---|
| `getMyBindings()` | `GET /family/bindings` | **无端点** |
| `requestBinding(input)` | `POST /family/bindings` | **无端点** |
| `getReports(relationId)` | `GET /family/reports` | **无端点** |
| `getReport(relationId, reportId)` | `GET /family/reports/{id}` | **无端点** |
| `getTrend(relationId)` | `GET /family/trend` | **无端点** |
| `getCarePlan(relationId)` | `GET /family/care-plan` | **无端点** |
| `getMessages()` | `GET /family/messages` | **无端点**，端侧显式返回空数组 |

机构侧另有 3 个方法同属缺口（`mockElders()` / `mockHomeStats()` / `mockCarePlan(name)`）：后端**无 elder 模块、无 dashboard 域**。
短信 / Apple / 微信换证 3 条登录路径亦无后端适配器（见 CR-M2-004 的背景节）。

## 二、证据

```bash
# 1. 后端端点全量清点：只有 auth 与 evaluation 两族，无任何 family / elder / dashboard 路径
grep -rn --include=*.java -E "@(Get|Post|Put|Delete|Patch)Mapping" yl-server | grep -v target

# 2. 接口契约同样没有家属端路径
grep -rn -i -E "family|bind|trend" yl-server/openapi/yl-api.yaml     # 无命中

# 3. 模块清单：yl-modules 下没有 yl-module-elder / yl-module-family
ls yl-server/yl-modules
```

结论：这不是"端侧漏改"，而是**后端供给侧确实没有这些端点**。

## 三、为什么必须显式登记，而不是"先随便 Mock 一下"

端侧 `real-api.ts` 的处理口径（本次实施）：

1. **显式委托**三端既有的家属端 Mock 层 `utils/family.ts`（其内部已实现"仅 ACTIVE 绑定可见 / 查询带 relationId 校验 / 到期即失效"三条越权防护）；
2. 每个函数上方**逐条标注缺口**并指向本卡编号；
3. 无法委托的（`getMessages`）**显式返回空数组**，绝不编造数据。

理由：缺口若被"看起来能用"的实现掩盖，会在 F3 验收时才暴露；而"静默造数"更危险 —— 家属端是**关系级鉴权**场景，
一旦端侧以 Mock 数据替代服务端校验，手工拼 URL 读取他人报告的越权面会直接上线。

## 四、后续动作（F3 开工前置）

1. **后端**：按 ER-01/ER-05 落地 `yl-module-elder`（档案域）与家属绑定五步状态机端点，补进 `openapi/yl-api.yaml`；
2. **权限矩阵**：补登家属端权限码（`family:binding:apply` / `family:report:read` 等），走 CR-M2-001 同一流程；
3. **端侧替换**：逐个把 `real-api.ts` 的委托改为真实调用；**替换时必须保留"返回前经 relationId 校验"的既有约束**，
   不得退化为"传空即全量"；
4. **验收**：家属端 F3 主链（申请绑定 → 审核 → 看报告 → 趋势 → 照护建议）跑通 + 越权用例（拼他人 reportId 必须被拒）。

## 五、本次实施结论

- 影响的验收项：P1-5 的"家属端 F3 报告 / 趋势接上" —— **改判为"端侧就绪，后端未供给"**，本卡承接；
- 不影响已验收范围：评估员侧四端主链（登录 → 录入 → 26 题 → 分级 → 报告 → 复核发布）已全部走真实接口并落库（见 P1-5 实施记录）。

---

## 六、D-4 开工裁决（2026-09-29）

### 6.1 ★核身通道（用户拍板）

| 方案 | 判定 | 理由 |
|---|---|---|
| **M2/dev：机构线下核验 + 代操作** | **✅ 采纳** | 绑定申请落库后由 `ORG_ADMIN` 经 `POST /family/bindings/{id}/approve` 放行（`account:family:bind` 已授予 ORG_ADMIN，**契约里本就有这个操作**）；**零新基建、零外部依赖**，E2E 当天可跑 |
| **短信通道** | **✅ 立独立卡 P2-8** | 按 `15_短信验证码通道技术方案.md` 立卡；涉**真实外发通道 + 手机号采集** → 触发合规评审；「**核身强度分级（线下 vs 短信）**」写为该卡的**生产前置** |
| **复用二次验证语义（`X-Second-Verify-Token`）** | **❌ 否决** | 它证明的是「**当前已登录会话的操作者**」，证明不了「**这个 elder 就是这个人**」——**语义不同构**，复用会让核身形同虚设 |

### 6.2 本卡范围的补充修正（地基核查的结论）

1. **契约缺 `elder↔user` 绑定写入的承载点** → D-4 需**新增 1 个操作** `POST /api/v1/elders/{elderId}/user-binding`（权限码复用 `account:org:manage`，零新码）。这是 R1 升格并入本卡后的**必然结果**，非范围膨胀。
2. **`authLogout` 一并落地**（契约 `x-status: planned`，注记"D-4 顺带"）→ 双令牌同时失效（access 黑名单 + `RefreshTokenStore.revoke`）。
3. **本卡"9 个操作"的冻结不变**（F1~F9）；`getMessages()` 仍**不登记**（V2）。
4. **零 DDL**：地基核查发现的 3 处数据域缺陷（`elder_family_bind` 在 scope=1 恒假、scope=2 静默放行、scope=5 下核验恒空）**全部用处理器内反查锚 + 独立只读投影 Mapper 闭合**，`verify-schema.sh` 保持 **83**。
5. **端侧落点修正**：`weapp` **不是** D-4b 的可改基准（仅 7 个文件、无 `family.ts`/`real-api.ts`/`api.ts`，且 `request.ts` md5 与 uni-app 三端不同）→ 实际路径为 **weixin-app 改一份 → md5 同步 douyin-app / ios-app**。

> 详见 `docs/reviews/D4-foundation-check.md`（含 9 个操作的 SQL 片段级推演与残留登记）。
