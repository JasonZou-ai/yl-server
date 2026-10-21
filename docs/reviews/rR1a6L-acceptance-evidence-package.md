# rR1a6L 验收证据包（交付测试负责人）

> **交付对象**：测试负责人（`rR1a6L` 唯一验收人，PM 2026-10-16 签发确权）
> **交付方**：研发侧（实施方）｜**交付日期**：2026-10-16｜**验收新硬限**：**2026-10-20 24:00**
> ⚠ **权责边界**：本包只提供证据与复现路径。**勾选权与结论权归验收人**；研发侧不得代填、代勾、代出结论（PM 签发 §一.1 / §三.2）。
> 仓库基线：**`main = 27bffe5`**（CI 全绿：run 38852626847 / 38852627003）｜PM 签发原文：`docs/reviews/PM-ruling-rR1a6L-20261016.md`
> ⚠〔2026-10-19 订正〕本文成文于 10-16 11:19，**早于**离线套件入库（12:48）与服务栈拉起；原记基线 `c558f0c` **已过时**，**当前基线为 `27bffe5`**。冒烟口径订正（127 → 130）见 `docs/reviews/switchA-smoke-caliber-130-20261016.md`。

---

## 零、三句话状态

1. 会签 ✅（CR-M2-009 五方，2026-10-15）、schema 授权断言 41 ✅（`rGn0xU`）⇒ **2008 解冻只剩你这一个前置**。
2. 读路径（开关 A）已于 2026-10-15 灰度上线并稳定运行，**不受本次验收影响**；验收只决定**开关 B（2008 写权限）**是否解冻。
3. PM 已裁决：**不以 Lead 复核替代正式验收**，门禁不降级 ⇒ 请以本包为准独立判定。

---

## 一、验收范围（PM 签发 §一.1 明确的六项）

| # | 验收项 | 对应清单条目 |
|:--:|---|---|
| 1 | ELDER 角色枚举（TS + Kotlin） | 清单 A-1 / A-2 |
| 2 | 兜底路由不再落入评估员工作台 | 清单 B 组（★ 本次必须修掉的缺陷） |
| 3 | 老人读面收敛（含本人报告列表接通） | 清单 C 组 + §六 S-1~S-5 |
| 4 | PDF 渲染 | 清单相关项（注：PDF 导出目前**仅说明按钮**，文件能力未接入 —— 属已知残留，见 §五） |
| 5 | 产物扫描（内测 / facePassed / 热线 / 手机号正则） | Lead 复核第 7 项 |
| 6 | 编译包（三端 + 安卓） | Lead 复核第 8 项 |

---

## 二、材料清单（全部在仓，按下文路径取）

| 材料 | 路径 |
|---|---|
| **验收清单**（勾选就在这份文件 §二） | `docs/reviews/CR-M2-009-rR1a6L-acceptance-checklist.md` |
| 改造后实证（Lead 独立复核 8 项，全绿） | `docs/reviews/rR1a6L-postchange-verification-2026-10-15.md` |
| 改造前基线（用于对照，非验收依据） | `docs/reviews/rR1a6L-precheck-verification-2026-10-14.md` |
| 后端第四批供给（本人报告列表端点） | `docs/reviews/rR1a6L-elder-report-list-endpoint-proposal.md` |
| 读路径上线记录（开关 A，已上线） | `docs/reviews/CR-M2-009-readpath-golive-record.md` |
| 上线条件清单（条件 1✅ / 2✅ / **3⏳ 待你**） | `docs/reviews/CR-M2-009-release-memo.md` §一 |
| PM 签发（本次确权与排期） | `docs/reviews/PM-ruling-rR1a6L-20261016.md` |
| 逾期预登记（10-17 场景 2 启用） | `docs/reviews/rR1a6L-acceptance-overdue-preregister.md` |
| 端侧改造点清单 | `docs/reviews/端侧对接org-contact改造点清单.md` |

**端侧源码根**（不在本仓）：`2026-09-08-13-00-34/stage3_开发/{weixin-app, ios-app, douyin-app, android-native}`

---

## 三、研发侧已完成的自证（供核对，**不等于验收通过**）

来自 `rR1a6L-postchange-verification-2026-10-15.md`（Lead 独立复核，8 项全绿）：

| # | 项 | 结果 |
|:--:|---|---|
| 1 | 三端共享 8 文件 md5 | ✅ 逐个 unique=1（`auth.ts` 由「三端互不相同」主动统一为 `793e50c5…`；`build-flag.ts` 未回退） |
| 2 | `role === 'FAMILY' ?` 穷尽替换（S-1） | ✅ 命中 9 处**全为注释** ⇒ 代码层 **0** |
| 3 | Kotlin `ROLE_ELDER` / 兜底删除 | ✅ `else ROLE_ASSESSOR` 仅剩注释；`ROLE_ELDER` 5 处落地 |
| 4 | C-2 不直读 `report_dimension_score` | ✅ 命中 4 处**全为禁止性注释** |
| 5 | 角色分发行为断言 | ✅ 三端各 **32 PASS / 0 FAIL / exit 0**（共 96 项） |
| 6 | 补丁链（Lead 自写实现） | ✅ 43/43 应用 → 差异 0；43/43 回滚 → 差异 0 ⇒ **PATCH-CHAIN-OK** |
| 7 | 三端生产产物 | ✅ 内测 / facePassed / 热线 = 0；手机号正则 weixin/douyin = 0、iOS 3 处为 vendor `9007199254740991` 子串；`elder-home` 已入产物；`IS_DEMO_BUILD=!1` |
| 8 | 安卓编译 | ✅ `compileDebugKotlin` **BUILD SUCCESSFUL**（`assembleDebug` 另出 APK 10,869,886 B） |
| + | C-4 回归（防误伤） | ✅ `DataScopePermissionHandlerTest 26/0` · `AccountAggregateServiceTest 6/0` · `AssessmentReportServiceTest 9/0` ⇒ 机构管理员/评估员未被状态闸误伤 |

> ⚠ 上述第 5 项曾有交付方自报「36 项」，实测为 **32 × 3 端**——已订正。请以实测为准，勿采信任何口头数字。

---

## 四、复现路径（研发侧提供，验收人可自行重跑）

```bash
# 1) 取仓内基线
cd <yl-server>; git rev-parse HEAD          # 应为 27bffe5（原记 c558f0c 已过时）

# 2) 后端门禁（研发侧已跑，可复核）
bash scripts/verify-openapi.sh                                   # 契约 8/8
MYSQL_BIN=/c/mysql/bin/mysql.exe MYSQL_PASSWORD=*** \
  bash scripts/verify-schema.sh                                  # 86/0/0，含「sys_role_permission = 41」

# 3) 后端单测（含 C-4 防误伤）
mvn -o test                                                      # 基线 372-0-0(+1)

# 4) 端侧产物扫描判据（三端各自执行，期望 0 命中）
grep -rn "400-000-0000\|facePassed\|1[3-9][0-9]{9}" <端侧>/dist   # 期望 0

# 5) 安卓编译
cd <android-native>; ./gradlew compileDebugKotlin                 # BUILD SUCCESSFUL
```

> **注意**：全量冒烟 `scripts/smoke-report-review-chain.sh`（130 用例）需 5 个环境变量：
> `MYSQL_BIN` / `MYSQL_PASSWORD` / `SECOND_VERIFY_DEV_CODE` / `EXPORT_DIR` / `DEV_LOGIN_PASSWORD`
> （脚本为 **fail-closed** 设计，缺任一直接 exit 1，**不会半跑出假绿**）。
>
> ⚠〔2026-10-19 订正〕原记「研发侧未持有 dev 口令、需向 PM 申领」**已过时**：研发侧已具备凭据
> （只从本机未跟踪文件 `/tmp/d4-run/env.sh` 注入，**AI 全程不接触、不记录明文**；项目硬约定：明文口令不进聊天）。
> **实跑记录**：2026-10-16 14:03 `130/0/0`、2026-10-18 10:39 `130/0/0`（每日巡检，自动化 `aed6baf4`）。
> 复跑命令（一键，脚本会自动推导 `MYSQL_BIN` 与 `EXPORT_DIR`）：
> `bash scripts/patrol-switchA-daily.sh /tmp/d4-run/patrol` ⇒ 期望「有效断言总数=130、失败 0」。

---

## 五、已知残留（**不构成通过**，请一并判定）

清单 §五 登记的残留项：

1. ~~后端未供给「本人报告列表」端点~~ → ✅ 已闭环（2026-10-15 第四批）：`GET /api/v1/evaluation/reports`（契约 48→49），端侧 `getMyReportsAsElder()` 已接通并过复核。
2. 小程序页面静态注册不拒载 ⇒ 靠**页面内守卫**（`home.vue` 未加渲染期兜底，仅一帧窗口）。
3. **PDF 导出仅说明按钮**（文件能力未接入）⇒ 验收范围第 4 项请据此判定。
4. 真机 D-1~D-3 **未跑**。
5. 平台特有文件 md5 三端不同**属预期**。
6. 机构联系方式端点已供给（`org:contact:read`），但 `OrgContactView` **不含电话字段** ⇒ 端侧咨询电话维持占位文案（红线 3）。

---

## 六、验收口径（请务必遵守，否则验收不成立）

1. **给出 `file:line`**：每项须附改造后源码位置，不接受「已支持 ELDER」的口头结论（清单 §三.1）。
2. **须核 §六 补充判据 S-1~S-5**，勿据「源码看起来对」勾选（清单 §五）。
3. **勾选框仅你可填**。实施方（含 AI）代填即失去独立验收意义。
4. **判「不通过」时请写明未通过项**，便于整改后复检。
5. 验收结论产出前，**开关 B 维持冻结**；这不影响已上线的读路径。

---

## 七、回填位置（验收完成后）

| 位置 | 填什么 |
|---|---|
| `docs/reviews/CR-M2-009-rR1a6L-acceptance-checklist.md` §二 | A-1 / A-2 / A-3 状态列 + 验收日期 + 验收人 |
| `docs/reviews/CR-M2-009-release-memo.md` §一 条件 3 | `rR1a6L` 状态 ⏳ → ✅ |
| `docs/reviews/rR1a6L-acceptance-overdue-preregister.md` | 分支判定结果（若 10-17 前完成则转归档） |

> 若验收在 **10-17 24:00 之后**完成：按清单 §四 需**先登记逾期记录再开启**，**不得先开后补**。
> 新硬限 **2026-10-20 24:00**（PM 签发 §二.2 场景 2）。
