# P2-7 地基核查报告 · 孤儿文件回收

> 工序：地基核查（只读实读，不改任何代码/SQL）。此工序在本项目已 **4 次改变落点**，本次同样出现 2 处。
> 核查日：2026-10-09 ｜ 核查人：架构（lead）
> 卡片：`docs/change/BACKLOG-M2-P1P2.md` → 卡 P2-7 ·「孤儿文件回收（保留期链路扩展）」

---

## 1. 结论（TL;DR）

| # | 核查项 | 实读结论 |
|---|---|---|
| C1 | 落盘的有几个根？ | **2 个**：`report/`（报告 PDF）与 `export/`（导出 CSV）。**全仓文件写入点仅 2 处** |
| C2 | 卡片列的 2 个 `cos_key` 列够不够？ | **不够**。全仓实有 **6 个 `*_cos_key` 列**（分属 6 张表） |
| C3 | 报告 PDF 的"在用"锚点是什么？ | ★ **是 `eval_report.report_no`（推导键名），不是 `pdf_cos_key`** —— 下载端点**根本不读** `pdf_cos_key` |
| C4 | 导出文件的"在用"锚点是什么？ | `export_task.file_cos_key`。且 `export_task` **无 `deleted` 列**（不可软删）⇒ 行永不消失 |
| C5 | `expire_at` 现状 | 列已存在（`DATETIME NULL`），**D-3 只写值、无任何消费方**；`status=4(已过期)` **无写入口** |
| C6 | 有无现成"回收站 / 隔离目录"机制？ | **无**（全仓 0 命中）⇒ 须新建 |
| C7 | 模块落点在哪？ | 需跨"文件 IO + `eval_report`(evaluation) + `export_task`(report)" → 见 §5 |
| C8 | 存储根抽象是否可共用？ | ★ 两个写点**各自复制**了一份 `baseDir()`/`resolve()`；模块**只依赖 `yl-api`**（不能引 `yl-infrastructure`）⇒ 见 §5 |

**⇒ 本次核查改变落点的 2 处**：
1. **C3**：若按卡片原文只反查 `pdf_cos_key`，会**误删仍可下载的在用 PDF**（`pdf_cos_key` 为 NULL / 滞后的行，其文件仍被 `GET /reports/{reportNo}/pdf` 服务）。
2. **C2**：`*_cos_key` 列是 **6 个不是 2 个**；不做显式登记就落进"无归属列 ⇒ 静默放行"的老坑（与数据域登记同构）。

---

## 2. 存储面实读（C1 / C5 / C6）

### 2.1 两个写入点（唯一）

| 写入点 | 模块 | 键名 | 根目录配置 | 回退 |
|---|---|---|---|---|
| `ReportPdfService#write` | `yl-module-evaluation` | `report/<reportNo>.pdf` | `yl.report.pdf.dir` | `${java.io.tmpdir}/yl-reports` |
| `SuperviseExportService#write` | `yl-module-report` | `export/<taskNo>.csv` | `yl.export.dir` | `${java.io.tmpdir}/yl-exports` |

实读证据（全仓 `Files.write` 仅 2 处，非测试）：
- `ReportPdfService.java:309`
- `SuperviseExportService.java:244`

两者均为 `yml` 中 **`${REPORT_PDF_DIR:}` / `${EXPORT_DIR:}` 留空 ⇒ 回退 tmpdir**。故**未配置时两个根都在 `%TEMP%` 下**——与 2026-09-26 手工清理时观察到的 `%TEMP%/yl-reports/report/` 完全吻合。

> ⚠ **踩坑记录（本次核查自身）**：`grep -rn ... yl-*/src/main` 在本仓库**只展开出 4 个顶层目录**（`yl-api` / `yl-bootstrap` / `yl-common` / `yl-infrastructure`），**完全漏掉 `yl-modules/*/src/main`**。前几条"0 写入口"因此是**假阴**（`proof_cos_key` 实际有 2 处写入口被漏掉）。**正确姿势：根用 `.` + `| grep -v /target/`**。

### 2.2 隔离 / 回收站机制
全仓 `trash|quarantine|回收站` = **0 命中** ⇒ 本卡须自建"隔离目录"，并承接 2026-09-26 手工"移入回收站"的口径。

### 2.3 `expire_at` 与 `status=4`
- `export_task.expire_at DATETIME NULL COMMENT '文件过期清理时间'`；`SuperviseExportService:153` 写 `now.plusDays(expireDays)`（默认 7 天）。
- `export_task.status` 注释含 `4-已过期`，但**全仓无任何地方写 4** ⇒ 与 ADR-0004 记录过的"有动作无写入口"同型。
- ⇒ **expire_at 目前是纯标记列**；其"清理语义"按卡片归本卡落地（见 D1）。

---

## 3. 引用面实读（C2 / C3 / C4）

### 3.1 全仓 6 个 `*_cos_key` 列（**卡片只提了 2 个**）

| # | 表.列 | 写入口（实读，根=`.`） | 是否落盘 | 结论 |
|---|---|---|---|---|
| 1 | `sys_user.avatar_cos_key` | **0** | 否 | 死列 |
| 2 | `elder.photo_cos_key` | **0** | 否 | 死列 |
| 3 | `elder_family_bind.proof_cos_key` | `FamilyBindingService:180/218` | **否**（D4-R4：只存键，全仓无 COS 实现） | 有写入、无文件 |
| 4 | `eval_evidence.cos_key`（**NOT NULL**） | **0**（且无实体类、表未接线） | 否 | 死表 |
| 5 | `eval_report.pdf_cos_key` | `ReportPdfService:112` | **是**（`report/`） | ★ 在清扫范围 |
| 6 | `export_task.file_cos_key` | `SuperviseExportService:148` | **是**（`export/`） | ★ 在清扫范围 |

⚠ **语义陷阱**：`proof_cos_key` / `avatar_cos_key` / `photo_cos_key` / `eval_evidence.cos_key` 目前都"没有对应文件"，所以**只扫 2 列在当下不会出事**；但一旦将来接 COS 或补写入实现，这些键指向的文件就会**被"未登记即视为无引用"的清扫器误删**。
⇒ 数据域治理的同一判据适用：**无归属列 ⇒ 静默放行**。故必须**显式登记引用源列**（不留暗区），并加静态守卫（新增 `*_cos_key` 列须同步登记）。

### 3.2 ★ 报告 PDF 的"在用"锚点是 `report_no`，不是 `pdf_cos_key`

实读链路：
```
ReportController.download(reportNo)                     ReportController.java:46-48
  └─ ReportPdfService#read(reportNo)                    ReportPdfService.java:136
       ├─ requireVisible(reportNo)                      ReportPdfService.java:158
       │    └─ reportMapper.selectOne(report_no = ?)    ← 全走全局逻辑删除（deleted=0）
       └─ resolve("report/" + reportNo + ".pdf")        ReportPdfService.java:141  ← ★ 键名由 reportNo 推导
```

- `generate()` 里 `key = "report/" + view.getReportNo() + ".pdf"`（L104）与 download 的推导**同式**。
- **download 路径完全不读 `pdf_cos_key`** ⇒ 只要 `eval_report`（`deleted=0`）行在，其 `report/<report_no>.pdf` 就在用——**哪怕 `pdf_cos_key` 为 NULL 或与推导键不一致**。
- 反例场景（会造成**误删**）：`generate()` 先 `write()` 成功（L105）、随后 `updateById` 失败（L114）⇒ 文件已在盘上、`pdf_cos_key` 仍为 NULL、但 download 可正常服务。**只反查 `pdf_cos_key` 的清扫器会把它当孤儿删掉。**
- 全局逻辑删除配置：`application.yml:31-33`（`logic-delete-field: deleted` / `value: 1` / `not-delete-value: 0`）；全仓 **0 处** `@TableLogic`（全走全局配置）。
- ⇒ **在用集合 = { `deleted=0` 行的 `report_no` 推导键 } ∪ { 非空 `pdf_cos_key` }**（并集，防滞后/不一致）。

### 3.3 导出文件的在用锚点
- `ExportTask` **无 `deleted` 字段** ⇒ 不受全局逻辑删除影响 ⇒ **行永不消失**。
- `SuperviseExportController` **只有 `POST`（创建）**，全仓**无导出下载/列表端点** ⇒ 导出 CSV 目前是"只写产物"，其唯一"引用"就是 `export_task` 行的存在。
- ⇒ 若严格按"只回收无 DB 行引用的文件"，则导出根**永远无孤儿**、动作恒空转、`expire_at` 继续当死列 —— 与卡片"`expire_at` 清理语义整体归本卡"冲突。**此为 D1 裁决点。**

---

## 4. 安全面（红线映射）

| 卡片红线 | 实读支撑 | 落地手段 |
|---|---|---|
| ① 绝不先删文件后删行 | `eval_report` 有 `deleted`；`export_task` 无 | 本动作**只动文件**，不删/不改 DB 行（除 D1-B 的 `status→4`） |
| ② 必须先反查 DB 确认无引用 | §3.2 证明"引用"≠"cos_key 列" | 用**推导键 ∪ 列值**的并集反查（D2） |
| ③ 遍历限定在配置存储根内，禁通配删除 | 两写点已有 `resolve()` 防穿越 | 复用同一套 resolve 语义（D3），只按**白名单枚举**逐个判断，**不做 `Files.walk` 通配删除**；符号链接跳过（`NOFOLLOW_LINKS`）**仅平台支持时生效** —— ⚠ 实测本机 Windows 文件系统不支持真符号链接（`createSymbolicLink`/`mklink` "假成功"、落成 0 字节普通文件），该分支由 Linux CI 覆盖；`Files.move` 不跟随链接，根外目标不受影响 |
| ④ 保留可还原窗口 | 全仓无隔离机制 | 新建隔离目录（D4） |

---

## 5. 模块落点与依赖（C7 / C8）

**依赖事实**：`yl-module-evaluation` / `yl-module-report` 均**只依赖 `yl-api`**（实读 `pom.xml`）。⇒ 把共享存储抽象放 `yl-infrastructure` **会破坏模块分层**（需给两个模块加依赖）。

**可行落点**：
- **先例**：`PdfFontLocator` 位于 **`yl-api`**，其 Javadoc 明写理由——"`modules → api`，反向引用会成环；候选列表因此收敛到此处作**唯一真源**"。⇒ **`yl-api` 是本仓约定的"跨模块共享基础设施"落点**。
- 任务类落点：`yl-module-report`（报表/导出域，导出是主力）**或** `yl-module-archive`。跨域取数用**模块自持只读投影 Mapper**（手写 `@Select`，逐列照 `02_schema.sql`），不违 ADR-0004。
- 触发壳：仍只有 `RetentionChainScheduler` 一处 `@Scheduled`（ADR-0004），新增**第 5 个动作**。

**难点（C8）**：两个写点**各自复制**了 `baseDir()` + `resolve()`（含 tmpdir 回退与防穿越）。若清扫器**第三次复制**同样的解析逻辑，则"写在哪"与"扫哪里"可能漂移 ⇒ 清扫器**静默空转**（扫了空目录）或**扫错目录**。这是与 CR-M2-006（不经 Mapper 的直读绕过治理）**同类的"副本漂移"缺陷**。⇒ 见 D3。

---

## 6. 待裁决点（提请教判）

| # | 裁决点 | 推荐 | 理由 |
|---|---|---|---|
| **D1** | `export/` 根的回收触发口径 | **B** | 见下 |
| **D2** | `report/` 根的"在用"判定锚 | **A** | 与 download 真实读取口径一致，防误删 |
| **D3** | 存储根解析是否统一抽象 | **A** | 防"扫的根 ≠ 写的根"静默漂移 |
| **D4** | 回收去向 | **A** | 同盘 move 原子、可还原、跨平台 |

- **D1**：
  - **A** 仅"无 `export_task` 行"才回收 ⇒ `expire_at` 保持死列、动作恒空转（**不推荐**）
  - **B**（推荐）`expire_at < now` ⇒ 回收文件 **且** 把行 `status` 置 `4`（已过期）⇒ 让列真正生效、与卡片一致
  - **C** `expire_at < now` ⇒ 只回收文件、不改行 ⇒ 会出现"行 `status=2` 完成、文件已不在"的**不一致**
- **D2**：
  - **A**（推荐）`report_no` 推导键 **∪** `pdf_cos_key`
  - **B** 仅 `pdf_cos_key`（卡片原文；**会误删在用文件**）
- **D3**：
  - **A**（推荐）抽 `yl-api` 下的小型对象键解析器（先例 `PdfFontLocator`），**两写点 + 清扫器三处共用**
  - **B** 最小侵入：清扫器自行读两个配置项（第三份重复逻辑，存在漂移风险）
- **D4**：
  - **A**（推荐）各根下 `.trash/<yyyyMMdd>/`（同盘 `Files.move`，原子、可还原）
  - **B** 独立配置目录 `yl.retention.orphan-file.trash-dir`
  - **C** OS 回收站（Windows 需 .NET/PowerShell；本沙箱 `Add-Type` 已被拦，**跨平台成本高**）

**另（非裁决，直接采纳）**：**6 个 `*_cos_key` 列全部显式登记**为引用源 + 静态守卫（新增列须同步登记），避免"未登记即视为无引用"的暗区。

---

## 7. 验收判据（拟，交 QA 独立复核）

1. 构造孤儿文件（`report/` 与 `export/` 各 ≥1）→ 跑一次 → **被移入隔离目录**（根下已不存在）。
2. **有 DB 引用的文件未动**：① `deleted=0` 行的 `report/<reportNo>.pdf`；② **`pdf_cos_key` 为 NULL 但行存在**的 PDF（★ 负例，验 D2）；③ 未过期的 `export_task` 文件。
3. `expire_at < now` 的导出文件 **被回收且行 `status=4`**（验 D1）。
4. 重复跑幂等（第 2 次 0 个）。
5. 与既有 4 动作在**同一次调度内**完成（链路日志含五计数）。
6. **还原路径可用**：从隔离目录 move 回原键名 → download / 文件可读。
7. 遍历不越界：根外文件、符号链接目标**不受影响**。
8. DB 级门禁：新增清算 SQL/条件须进 `RetentionChainDbIT`（承 D5-R1 教训——mock 单测对手写 SQL 零区分力）。
