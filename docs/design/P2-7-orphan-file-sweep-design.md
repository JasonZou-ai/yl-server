# P2-7 设计 · 孤儿文件回收（保留期链路第 5 个动作）

> 前置：`docs/reviews/P2-7-foundation-check.md`（地基核查，已改变落点 2 处）
> 裁决（用户，2026-10-09）：**D1 = B**（到期即回收 + `status→4`）｜**D2 = A**（`report_no` 推导键 ∪ `pdf_cos_key`）｜**D3 = A**（存储根解析统一抽象到 `yl-api`）｜**D4 = A**（各根下 `.trash/<yyyyMMdd>/`）
> 铁律：门禁只升不降；不得改既有断言；残留必须显式登记；凭据不得内联进命令。

---

## 1. 目标与非目标

**目标**：给保留期链路加**第 5 个动作**——把「存储根里已无 DB 引用的文件」移入隔离目录（**不 `rm`**），使「文件」维度与「DB 行」维度享有同一套保留期治理。

**非目标**：
- 不接 COS（全仓仍无 COS 实现，键名口径不变，将来只换写入实现）。
- 不新增 `@Scheduled`（ADR-0004 唯一触发壳不变）。
- 不改任何读端点语义（download / 导出）。
- **不删 DB 行**（唯一例外：D1-B 的 `export_task.status → 4` 标记，属业务列、非删除）。

---

## 2. 口径定义（先定死，再实现）

| 概念 | 定义 |
|---|---|
| **存储根** | 根 A `report/` ← `yl.report.pdf.dir`（回退 `${java.io.tmpdir}/yl-reports`）；根 B `export/` ← `yl.export.dir`（回退 `${java.io.tmpdir}/yl-exports`）。**只有这 2 个根**（全仓仅 2 处 `Files.write`） |
| **键（key）** | `前缀/文件名`，如 `report/YL20261009xxxx.pdf`、`export/EXP20261009xxxx.csv` |
| **在用（根 A）** | `key ∈ { eval_report(deleted=0).report_no → "report/"+report_no+".pdf" } ∪ { eval_report 非空 pdf_cos_key }`。**并集**（D2-A）。理由：`GET /reports/{reportNo}/pdf` 按 `report_no` 推导键名、**不读** `pdf_cos_key` |
| **在用（根 B）** | `key ∈ { export_task(status≠4).task_no → "export/"+task_no+".csv" } ∪ { export_task 非空 file_cos_key }` |
| **到期** | `export_task.expire_at IS NOT NULL AND expire_at < now AND status ≠ 4` ⇒ 先标记 `status = 4`（已过期），随后其键**退出在用集合** ⇒ 文件可回收（D1-B） |
| **孤儿** | 位于根 A/B 内、且 **key ∉ 对应在用集合** 的**普通文件** |
| **回收** | `Files.move` 到 `<baseDir>/.trash/<yyyyMMdd>/<前缀>/<文件名>`（**同盘原子、可还原**，D4-A） |

**引用源列登记（不留暗区，直接采纳）**：全仓 `*_cos_key` 列实有 **6 个**，全部登记为"引用源"：

| 表.列 | 现状 | 处理 |
|---|---|---|
| `eval_report.pdf_cos_key` | 落盘（根 A） | 在用集合（并集之一） |
| `export_task.file_cos_key` | 落盘（根 B） | 在用集合（并集之一） |
| `elder_family_bind.proof_cos_key` | 有写入口、**不落盘**（D4-R4） | 登记（防将来接 COS 后误删） |
| `sys_user.avatar_cos_key` | 无写入口 | 登记 |
| `elder.photo_cos_key` | 无写入口 | 登记 |
| `eval_evidence.cos_key`（NOT NULL） | 无写入口、表未接线 | 登记 |

⇒ 登记以 **`verify-schema.sh` 断言**固化：`information_schema` 中 `%cos_key` 列集合必须等于登记集；**新增列须同步登记**，否则门禁变红。

---

## 3. 架构与落点

### 3.1 `yl-api` 新增对象键解析器（D3-A，唯一真源）

`cn.yl.api.storage.ObjectKeyResolver`：

```java
public final class ObjectKeyResolver {
    private final Path baseDir;
    /** @param configuredDir 配置目录（空 → 回退 ${java.io.tmpdir}/<fallbackSubDir>） */
    public ObjectKeyResolver(String configuredDir, String fallbackSubDir);
    public Path baseDir();
    /** 键 → 绝对路径；越界（目录穿越）抛 BizException(PARAM_INVALID, "非法存储路径: " + key) */
    public Path resolve(String key);
}
```

**为什么放 `yl-api`**：两模块只依赖 `yl-api`（实读 pom），不能引 `yl-infrastructure`；先例 `PdfFontLocator` 的 Javadoc 已把这条约定写死（"modules → api，反向引用会成环"）。

**两写点改造（**纯替换、零行为变更**）**：
- `ReportPdfService`：`new ObjectKeyResolver(storageDir, "yl-reports")`，`resolve()/baseDir()` 全部改调它；
- `SuperviseExportService`：`new ObjectKeyResolver(exportDir, "yl-exports")`。

> ★ 这是**回收器正确性的前提**，不是美化：若清扫器自行复制第三份 `baseDir()+resolve()`，一旦写入侧配置口径变化，清扫器会**静默扫空目录**（看起来"没有孤儿"）——与 CR-M2-006「不经 Mapper 的直读绕过治理」同类的"副本漂移"缺陷。

### 3.2 任务落点：`yl-module-report`

该动作需跨「文件 IO + `eval_report`(evaluation) + `export_task`(report)」。按 ADR-0004 与 D-5 判例：
- 业务动作落在**模块**（`yl-module-report`，导出域，主力）；
- 跨域取数用**模块自持只读投影 Mapper**（手写 `@Select`，逐列照 `02_schema.sql`），不违 ADR-0004；
- 触发壳仍只有 `yl-bootstrap` 的 `RetentionChainScheduler`。

### 3.3 新增类清单

| 类 | 位置 | 职责 |
|---|---|---|
| `ObjectKeyResolver` | `yl-api/.../api/storage/` | 根解析 + 键→路径 + 防穿越（唯一真源） |
| `RetentionReferencePort`（端口） | `yl-module-report/.../domain/retention/` | 2 方法：`referencedReportKeys()` / `referencedExportKeys()` |
| `ExpiredExportTaskReaper`（端口） | 同上 | 2 方法：`markExpired(cutoff, limit)` / `countExpired(cutoff)` |
| `RetentionReferenceMapper` | `yl-module-report/.../mapper/` | 手写 `@Select`/`@Update`，**显式 `deleted = 0`** |
| `OrphanFileSweepTask` | `yl-module-report/.../service/retention/` | 普通方法 `sweepOrphanFiles()`，**无 `@Scheduled`** |

**实现类**（端口的两个实现）也放 `yl-module-report`（模块内即可，无需 infrastructure）。

---

## 4. 动作设计：`OrphanFileSweepTask#sweepOrphanFiles()`

```
1. cutoff = now
2. dry-run? → 只统计（rootA 孤儿数 / rootB 孤儿数 / 到期导出任务数）→ 返回 0
3. 【DB 先行】按 max-rounds 限批把 export_task 到期行 status → 4   （reaper.markExpired）
4. 【取在用集合】referencedReportKeys() / referencedExportKeys()   （此刻 status=4 行已不在集合内）
5. 【扫根 A】report 根：仅列 report/ 一层 → 每个普通文件 key ∉ 在用集合 ⇒ 移入 .trash
6. 【扫根 B】export 根：仅列 export/ 一层 → 同上
7. 返回 rootA+rootB 实际回收文件数
```

**硬约束**：
- **只列一层**（`baseDir/<前缀>/`），**不做 `Files.walk` 递归**、**不用通配删除**（红线③）；
- 只处理 `Files.isRegularFile(p, NOFOLLOW_LINKS)` ⇒ **在支持符号链接的平台**符号链接被跳过（防逃逸），目录亦跳过；
  - ⚠ **平台限制（2026-10-09 实测，非缺陷）**：本机 Windows 文件系统**不支持真正的符号链接**且是"假成功"式降级 —— `Files.createSymbolicLink` 与 `mklink` 都**不抛异常**，却落成 **0 字节普通文件**（随后 `readSymbolicLink` 抛 `NotLinkException`、`isSymbolicLink` 恒 `false`、`isRegularFile(NOFOLLOW)` 恒 `true`）。故该分支**在本机无法构造输入**，由支持符号链接的 Linux CI 覆盖；对应单测以 `isSymbolicLink` 为唯一判据**确定性跳过**（否则同一提交会因 shell 权限不同产出 SUCCESS/FAILURE 两种结果 —— **门禁 flaky 比红更糟**）。即便降级为普通文件，`Files.move` 也只搬移文件本身、**不跟随**链接，根外目标不受影响。
- 移动目标在**同一 `baseDir` 内**（`.trash/`）⇒ `Files.move` 原子、不跨盘；
- 单个文件失败 → `warn` 并继续（**失败不抛**，红线：不阻断其它文件）；
- 整体异常 → `error` + 返回 0，下周期重试；
- `.trash/` 不在 `report/`、`export/` 前缀内 ⇒ **不会被二次扫描**（天然幂等）；
- 限批 `batch-size`（每轮每根最多 N 个）+ `max-rounds` 兜底。

**幂等性**：命中即移出根 ⇒ 第二次扫描该文件不再出现，无需分布式锁（承 ADR-0004 三条硬约束）。

---

## 5. 配置（`yl.retention.orphan-file.*`）

```yaml
orphan-file:
  enabled: ${RETENTION_ORPHAN_FILE_ENABLED:true}
  batch-size: ${RETENTION_ORPHAN_FILE_BATCH:500}
  max-rounds: ${RETENTION_ORPHAN_FILE_MAX_ROUNDS:20}
  dry-run: ${RETENTION_ORPHAN_FILE_DRY_RUN:false}
  # 隔离子目录名（相对各存储根），默认 .trash
  trash-subdir: ${RETENTION_ORPHAN_FILE_TRASH:.trash}
  # 导出任务到期标记（D1-B）：true 时才把 expire_at<now 的行置 status=4
  export-expire-mark: ${RETENTION_ORPHAN_FILE_EXPORT_MARK:true}
```

存储根本身**复用既有属性**（`yl.report.pdf.dir` / `yl.export.dir`），**不新增第二套配置**（否则又一处漂移源）。

---

## 6. 门禁影响

| 载体 | 变更 | 期望 |
|---|---|---|
| 单测（新增） | `OrphanFileSweepTaskTest`（真 tmp 目录 + 假端口）：孤儿回收 / **`pdf_cos_key` 为 NULL 但 `report_no` 在用 ⇒ 不动**（D2 负例）/ 限批 / 幂等 / dry-run / 失败隔离 / 符号链接跳过 / `.trash` 不重复扫 | 全绿 |
| `RetentionChainSchedulerTest` | 反射断言扩到 **5 个任务方法均无 `@Scheduled`**；编排/审计改**五计数** | 全绿 |
| `RetentionChainDbIT`（DB 级） | 新增 **T7**：`export_task` 到期 `status 2→4`（未到期 / 已 4 / `expire_at IS NULL` 不动）；**T8**：`eval_report` 投影**排除 `deleted=1`**（手写 `@Select` 不走全局逻辑删除 ⇒ 必须显式写 `deleted = 0`，此条即其守卫）；**T9**：`referencedExportKeys()` 不含 `status=4` 行 | 全绿 |
| `verify-schema.sh` | 新增断言：`%cos_key` 列集合 == 登记集（6） | 84 → **86**（+2：列集合 + 登记点提示） |
| `verify-openapi.sh` | **无新端点** ⇒ 零改动 | 8/8（44/0，漂移 0） |
| `mvn -o verify` | +新单测 | **270 → 只升不降** |
| Checkstyle / SpotBugs | 新类过 AOSP + Medium+ | 0/0 |
| ADR-0004 | **动作数 4 → 5** ⇒ 触发"≥3 须复评是否迁 XXL-Job"，须显式复评并回写 | 结论预期仍「不迁」（只有 1 处 `@Scheduled`、动作朴素） |

---

## 7. 验收判据（拟，交 QA 独立复核）

1. 构造孤儿（根 A、根 B 各 ≥1）→ 跑一次 → **rootA+rootB = 实际回收数**，根下已不存在，`.trash/<日期>/` 下可寻回。
2. **有引用未动**：① `deleted=0` 行的 `report/<reportNo>.pdf`；② **`pdf_cos_key` 为 NULL 但行存在**的 PDF（★ D2 负例）；③ `status≠4` 的导出文件；④ **根外文件**不受影响，符号链接目标不受影响（符号链接分支**仅平台支持时生效**，见 §4 平台限制）。
3. `expire_at < now` 的导出文件 **被回收且行 `status=4`**（D1-B）。
4. **重复跑幂等**（第 2 次 0 个）。
5. **与既有 4 动作在同一次调度内完成**，链路日志含**五计数**。
6. **还原路径可用**：从 `.trash` move 回原键名 → 文件可读。
7. `dry-run=true` 时**零移动**、只输出统计。
8. DB 级门禁：新增手写 SQL 进 `RetentionChainDbIT`（承 D5-R1：mock 单测对手写 SQL 零区分力）。
9. **突变证明**：至少 3 条突变（摘 `deleted = 0` / 摘 `status <> 4` / 把 `move` 改成 `delete`）⇒ 门禁必须变红。

---

## 8. 任务分解（串行）

| # | 任务 | 依赖 |
|---|---|---|
| **T1** | `yl-api` 新增 `ObjectKeyResolver`；`ReportPdfService`/`SuperviseExportService` **纯替换**（零行为变更） | — |
| **T2** | `yl-module-report`：`RetentionReferenceMapper`（手写 SQL，显式 `deleted=0`）+ 两端口 + 实现 | T1 |
| **T3** | `OrphanFileSweepTask`（DB 先行 → 取集合 → 两根扫 → 隔离目录）+ 单测 | T2 |
| **T4** | `RetentionChainScheduler` 接入**第 5 动作**（五计数 + 门槛）+ yml 配置块 | T3 |
| **T5** | 门禁同步：`RetentionChainSchedulerTest` 五动作、`RetentionChainDbIT` T7–T9、`verify-schema.sh` +2、ADR-0004 复评、BACKLOG/卡面回填 | T4 |

---

## 9. ADR-0004 复评（动作 4 → 5）

- 触发条件：动作数 ≥ 3（现 4 → 落地后 **5**）。
- 复评要点：① 仍只有 **1 处** `@Scheduled`（壳）；② 5 个动作均为**幂等 + 限批 + 失败不抛**的朴素批处理，无依赖编排、无重试编排、无分片需求；③ 新增动作**不引入分布式协调**（命中即离开命中集合）。
- 预期结论：**不迁 XXL-Job**；在 ADR-0004 追加"第 2 次显式复评（2→4 之后）"记录。

---

## 10. 残留（预登记，实施后据实修订）

| 编号 | 内容 | 处置 |
|---|---|---|
| P2-7-R1 | 隔离目录**无自动过期清理**（`.trash` 会累积） | 保持残留：保留可还原窗口优先；后续可为其加"隔离 N 天后清空"的独立动作（**会再推高动作数**） |
| P2-7-R2 | `proof_cos_key` / `avatar_cos_key` / `photo_cos_key` / `eval_evidence.cos_key` **无对应文件**，登记为引用源属**防御性** | 保持：登记成本≈0；已在 §2 说明 |
| P2-7-R3 | 导出文件**无下载端点** ⇒ 其"在用"仅由行存在性定义 | 保持残留（D3-R3 同批：异步大导出无实现） |
| P2-7-R4 | 回收动作**不与 DB 事务同界**（先改 `status` 再移文件；中间崩溃 ⇒ 行已 4、文件仍在根内 ⇒ 下次扫描仍会回收，**收敛**） | 保持残留：定性为"最终收敛"，非缺陷 |
| **P2-7-R5** | 符号链接「跳过」分支**本机不可构造输入**（Windows 文件系统"假成功"降级为普通文件）⇒ **本地门禁恒跳过** | 保持残留：判据已改为 `Files.isSymbolicLink` **确定性跳过**（不 flaky）；该分支由**支持符号链接的 Linux CI** 覆盖（见 §4 平台限制） |
| **P2-7-R6** | `markExpired` / `countExpired` 的 `expire_at IS NOT NULL` **行为层不可区分**（QA 突变 M4 实证：摘掉它门禁不变红）**且无静态守卫**（T5 原只覆盖 4 个既有 Mapper） | **✅ 已闭环（2026-10-09）**：`RetentionChainDbIT#T5` 静态守卫**扩到第 5 个 Mapper**（断言 `markExpired` SQL 仍含 `expire_at IS NOT NULL`）；并**用突变反向证明该守卫有区分力** —— 摘掉该条件后 `Failures: 1`、红的正是 `t5SqlGuardsPresentForAllFourMappers`（此前 M4 为绿）|

> 备注（数据卫生，非缺陷）：`T7` 调用的 `markExpired` 是**全表限批 UPDATE**，而 `@AfterEach` 只按用例 id 前缀清理 ⇒ 在含真实到期数据的 dev 库上跑本 IT 会把那些行也置 4。此为「限批全表扫描」语义的固有属性，与 `T1`–`T4` 调用的真实任务一致（`T1` 的 `purged == 2` 本就隐含"库内无其它到期行"）。**只影响 dev 库、不影响生产**（IT 不进默认套件）。
