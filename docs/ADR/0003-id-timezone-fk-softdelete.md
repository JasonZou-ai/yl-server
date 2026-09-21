# ADR-0003 数据模型全库口径：主键策略 / 时区 / 引用完整性 / 软删除

- 状态：已采纳
- 日期：2026-09-21
- 关联任务：B1-4 数据库 ER 设计（`r4UcEv`）· ER 三方评审 Action Item #9（ER-09 / ER-12 / ER-13）
- 依据：《ER 三方评审纪要》（2026-09-18）§五「P2 待登记项」；GB/T 42195-2022；PIPL / 数据安全法

## 背景

ER 三方评审在**结构层面全部通过**，但登记了 3 项**全库级口径**尚未成文的问题：

| ID | 议题 |
|---|---|
| ER-09 | 主键生成策略不一致（业务表应用层赋值 vs 种子表自增） |
| ER-12 | 时区策略（全库 `DATETIME`，无 `TIMESTAMP`） |
| ER-13 | 全库 0 外键约束下的引用完整性约定 |

这三项的共同特征是：**不由任何单张表决定，而由全库约定决定**。一旦不同批次建表各自为政，只能靠人工巡检发现，且事后回补成本极高——例如混入一个 `TIMESTAMP` 列，会在跨时区部署时静默产生 8 小时偏差。

评审同时确认：现状（`docker/mysql/init/*.sql`）**已经**按推荐口径实现，缺的只是把口径写下来、并让它**可被机器校验**。

本项目在 B1 阶段已付出过"门禁只写在文档里、从未真正执行"的代价（SpotBugs 曾长期只声明在 `pluginManagement`、`mvn verify` 从未跑到，详见 `ADR-0001`）。因此本 ADR **不采用「写文档 + 靠人遵守」**，而是把三条口径**做成 `scripts/verify-schema.sh` 的断言**，与 CI `schema-verify` 作业绑定。

## 决策

### 1. 主键策略（ER-09）

- **业务表**：主键 `id BIGINT NOT NULL`，**一律由应用层赋值**（雪花 ID / 号段），DDL 中**不得出现 `AUTO_INCREMENT`**。
  - 理由：后续分库分表与离线补录要求 ID 在应用层可控；自增键在数据合并时会冲突。
- **字典表 / 种子表**：允许自增。当前仅 `gb_rule_version`（国标规则版本种子表，不参与业务写入）。
- **不得混用**：同一张表要么应用层赋值，要么自增，不允许部分依赖数据库、部分依赖应用。

**现状**：全库 `AUTO_INCREMENT` 列 **= 1**（仅 `gb_rule_version`）。

### 2. 时区策略（ER-12）

- 时间列**统一 `DATETIME`**，**全库禁用 `TIMESTAMP`**。
  - 理由：`TIMESTAMP` 的存储值受会话时区影响，且存在 2038 年溢出问题；`DATETIME` 语义稳定、可预期。
- 时区在**四处固定为 `Asia/Shanghai`**，任一处不一致都会造成"读写差 8 小时"：

| # | 位置 | 配置 |
|---|---|---|
| 1 | JVM / Jackson 序列化 | `spring.jackson.time-zone: Asia/Shanghai`（`application.yml`） |
| 2 | JDBC 连接串 | `serverTimezone=Asia/Shanghai`（`application-dev.yml`） |
| 3 | 容器时区 | `ENV TZ=Asia/Shanghai` + `/etc/localtime` 链接（`Dockerfile`） |
| 4 | 数据库容器 | `TZ: Asia/Shanghai` + `--default-time-zone=+08:00`（`docker-compose.yml`） |

**现状**（实测 MySQL 8.0.37）：全库 `DATETIME` 列 **66**，`TIMESTAMP` 列 **0**。

### 3. 引用完整性与软删除（ER-13）

- **全库不使用物理外键（`FOREIGN KEY` = 0）**，引用完整性由**应用层**保证（写入前存在性校验 + 查询侧约束）。
  - 理由：物理外键与逻辑删除、批量导入、分库分表三个目标直接冲突；误删父行时的级联行为不可控。
- **禁止物理级联删除**（`ON DELETE CASCADE` 一并禁用）。删除一律走软删除。
- **软删除统一用 `deleted TINYINT NOT NULL DEFAULT 0`**，业务查询默认附 `deleted = 0` 条件。
- **软删除 × 唯一键的冲突，用生成列解决**：需要"删除后可重建同键"的表，加

  ```sql
  `active_uk` TINYINT GENERATED ALWAYS AS (IF(`deleted` = 0, 1, NULL)) STORED,
  UNIQUE KEY `uk_xxx` (<业务键...>, `active_uk`)
  ```

  原理：`deleted = 1` 时生成列取 `NULL`，而 SQL 语义中 `NULL` 互不相等 —— 历史行可并存，生效行仍受唯一约束。

**现状**：`deleted` 列 **19** 张表；`active_uk` 生成列 **3** 处 —— `elder_family_bind`（`uk_elder_family`）、`eval_report`（`uk_order`）、`care_plan`（`uk_report`）。

## 可执行门禁（本 ADR 的强制手段）

三条口径已写入 `scripts/verify-schema.sh`，CI `schema-verify` 作业每次在**真实 MySQL 8** 上重跑：

| 断言 | 期望值 | 对应 |
|---|---|---|
| 自增列总数 | **= 1**（仅 `gb_rule_version`） | ER-09 |
| `TIMESTAMP` 列总数 | **= 0** | ER-12 |
| `DATETIME` 列总数 | ≥ 60 | ER-12 |
| `FOREIGN KEY` 约束总数 | **= 0** | ER-13 |
| `deleted` 列总数 | ≥ 19 | ER-13 |
| `active_uk` 生成列数 | **= 3** | ER-13（软删除 × 唯一键） |

配套**行为验证**（非结构断言，共 5 步）：写入生效行 → 同键重复被拒 → 软删除后生成列取 `NULL` → 同键重建成功 → 历史行与生效行并存。

## 后果

- **正面**：三条全库口径从"会议结论"变成"会让构建失败的断言"。后续新增表若违反，CI 直接拦下，不依赖人工巡检。
- **代价（有意保留的摩擦）**：若确需新增自增列或调整 `deleted` 覆盖面，**必须同步修改断言期望值**并在此 ADR 记录理由。这道摩擦是为了让"例外"必须被显式声张，而不是静默扩散。
- **取代关系**：本 ADR 落实 ER-09 / ER-12 / ER-13 三项登记项；ER-05（`row_version` 乐观锁）与 ER-06（作答三语义）属**表级**设计，已在 `ER-20260918-DDL-Patch-Proposal.sql` 落地，不在本 ADR 范围。

## 未采纳方案

| 方案 | 未采纳原因 |
|---|---|
| 用物理外键保证引用完整性 | 与逻辑删除、批量导入、分库分表三个目标冲突；级联删除不可控 |
| 用 `TIMESTAMP` + 应用层换算时区 | 引入 2038 年溢出与会话时区依赖，两个都是静默故障源 |
| 软删除唯一键改为"删除时改写业务键"（追加时间戳后缀） | 破坏业务键语义与可读性，且使历史数据无法按原键检索 |
| 业务表改用自增主键 | 数据合并冲突；离线补录场景无法预分配 ID |
