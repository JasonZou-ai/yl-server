# SonarQube Quality Gate 阈值提案（v1.1 · 已定稿）

> 编号 **YL-M2-QG-PROPOSAL-v1.1**｜编制 2026-09-21｜状态：**已定稿（§8 定稿决议）**
> 依据：《需求基线冻结说明》§4（质量与变更治理）｜ADR-0001（静态分析门禁）｜ADR-0002（提交规范）
> 关联任务：M2-S1 平台底座（`rbcN8b`）｜未闭环项「SonarQube Quality Gate 阈值（M3 前定）」
> 平台：**自建 SonarQube（内网部署）** —— 定稿决议 §8 第 5 项，源代码不出域

---

## 0 TL;DR

1. **现状三个事实**：① 仓库 **0 个测试类**（24 个主源文件）；② **Jacoco 仅在 `pluginManagement` 声明、无 execution → 从未执行**，覆盖率数据根本不存在；③ CI 已有 `sonarqube` 作业，但**未配置 Quality Gate 等待与阈值**（当前"扫了不卡"）。
2. **口径**：采用 **Clean as You Code（以新代码为主）**——历史存量不设硬门槛，新代码必须达标，避免一次性背上技术债。
3. **建议分阶段**：M2 先卡「**新代码 0 Bug / 0 漏洞 / 安全热点 100% 复核 / 重复率 ≤3%**」；**覆盖率阈值 M3 起才生效**（测试补齐后），否则立刻全红。
4. **P1 观察项**（不阻断，周度评审）：全量覆盖率、技术债比率、全量重复率、高复杂度函数趋势。
5. **前置动作**：把 Jacoco 真正绑到 `verify` 并接入 Sonar 报告路径，否则覆盖率门禁是"空门"。

---

## 1 现状诊断（实测）

| # | 事实 | 证据 | 影响 |
|---|---|---|---|
| 1 | 无测试 | `find -path *src/test* -name *Test.java` = **0**；主源 24 个 Java 文件 | 覆盖率 = 0%，任何覆盖率门禁会立即阻断 |
| 2 | Jacoco 悬空 | `pom.xml` 中 `jacoco-maven-plugin` 仅在 `<pluginManagement>`（第 235–239 行），**无 `<executions>`** | 不产 `jacoco.exec`/`jacoco.xml`；Sonar 拿不到覆盖率 |
| 3 | Quality Gate 未接线 | `.github/workflows/ci.yml` 第 163–183 行扫描命令**无 `-Dsonar.qualitygate.wait=true`**，也未声明阈值 | 质量门禁"扫而不管"，红灯不影响 CI |

> ⚠️ 事实 2 与先前 **SpotBugs 悬空**（曾声明但从未执行）属同类问题——「配置存在 ≠ 门禁生效」，本提案一并纠正。

---

## 2 口径：Clean as You Code（只卡新代码）

| 作用域 | 含义 | 本提案用法 |
|---|---|---|
| **New Code（新代码）** | 相对基线（Previous Version / 指定日期）新增或修改的行 | **P0 硬门槛**——保证增量不劣化 |
| **Overall（全量）** | 全部代码 | **P1 观察项**——设阶段目标，不即刻阻断 |

**新代码窗口建议**：M2 阶段用「**Previous Version**」（每次发布打 tag）；M2 内未发布时用「**指定日期基线**（M2 开工日 2026-09-18）」。二者择一，评审时定。

---

## 3 P0 必过项（Quality Gate = ERROR，红灯即阻断合并到 main）

### 3.1 即时生效（M2 起，不依赖测试）

| # | 指标（Sonar 条件名） | 作用域 | 阈值 | 理由 |
|---|---|---|---|---|
| 1 | `new_bugs` / Reliability Rating | 新代码 | **= 0**（Rating = A） | 新引入缺陷一票否决 |
| 2 | `new_vulnerabilities` / Security Rating | 新代码 | **= 0**（Rating = A） | 隐私合规项目，安全零容忍 |
| 3 | `new_security_hotspots_reviewed` | 新代码 | **= 100%** | 涉敏感个人信息，热点必须人工复核 |
| 4 | `new_maintainability_rating` | 新代码 | **≥ A** | 防止可维护性劣化 |
| 5 | `new_duplicated_lines_density` | 新代码 | **≤ 3%** | 抑制复制粘贴（本项目 DDL/枚举易复制） |

### 3.2 M3 起生效（测试补齐后启用）

| # | 指标 | 作用域 | 阈值 | 前提 |
|---|---|---|---|---|
| 6 | `new_coverage` | 新代码 | **≥ 60%** | Jacoco 已接线 + M3 建立测试基线 |
| 7 | `coverage` | 全量 | **≥ 40%** | 同上 |

### 3.3 M4 起生效（上线前收紧）

| # | 指标 | 作用域 | 阈值 |
|---|---|---|---|
| 8 | `coverage` | 全量 | **≥ 60%** |
| 9 | Reliability / Security Rating | 全量 | **= A** |
| 10 | `new_coverage` | 新代码 | **≥ 80%**（核心模块：`yl-domain` / 分级引擎） |

> **豁免通道**：仅限 hotfix（阻断性缺陷/安全事故），须在提交信息标注 `fix(hotfix):` 并**事后 24h 内补测试**；豁免须记入 CCB 记录（对齐《冻结说明》§4.3「不得先改后补」的例外留痕要求）。

---

## 4 P1 观察项（WARN，不阻断；周度评审看趋势）

| # | 指标 | 作用域 | 观察阈值（告警线） | 目标 |
|---|---|---|---|---|
| 1 | `coverage` | 全量 | < 40% 告警 | M4 前 ≥ 60% |
| 2 | `sqale_debt_ratio`（技术债比率） | 全量 | > 5% 告警 | ≤ 5% |
| 3 | `duplicated_lines_density` | 全量 | > 5% 告警 | ≤ 5% |
| 4 | 认知复杂度 > 15 的函数数 | 全量 | 环比增长即告警 | 逐模块收敛 |
| 5 | Blocker / Critical Code Smell 数 | 全量 | > 0 即告警 | 随重构清零 |
| 6 | 新增代码行数 / 模块 | 新代码 | 单模块占比 > 50% 告警 | 防集中失控 |
| 7 | `new_lines_to_cover`（新增可覆盖行） | 新代码 | 持续为 0 告警 | 防止"只写代码不写测试" |

---

## 5 落地清单（前置动作，建议 M3 前完成）

### 5.1 把 Jacoco 真正绑到 `verify`（修正"悬空"）

```xml
<!-- pom.xml <plugins> 段（非 pluginManagement） -->
<plugin>
  <groupId>org.jacoco</groupId>
  <artifactId>jacoco-maven-plugin</artifactId>
  <executions>
    <execution>
      <id>jacoco-prepare</id>
      <goals><goal>prepare-agent</goal></goals>
    </execution>
    <execution>
      <id>jacoco-report</id>
      <phase>verify</phase>
      <goals><goal>report</goal></goals>   <!-- 产出 target/site/jacoco/jacoco.xml -->
    </execution>
  </executions>
</plugin>
```

### 5.2 新增 `sonar-project.properties`

```properties
sonar.projectKey=JasonZou-ai_yl-server
sonar.organization=jasonzou-ai
sonar.sourceEncoding=UTF-8
sonar.java.binaries=**/target/classes
sonar.coverage.jacoco.xmlReportPaths=**/target/site/jacoco/jacoco.xml
sonar.exclusions=**/target/**,**/generated/**,**/*Application.java,**/dto/**
sonar.qualitygate.wait=true
```

### 5.3 CI 接入门禁等待

在 `.github/workflows/ci.yml` 的 `sonarqube` 作业扫描命令追加 `-Dsonar.qualitygate.wait=true -Dsonar.qualitygate.timeout=600`，
使 **Quality Gate 红灯直接令 CI 失败**（否则仍是"扫而不管"）。

### 5.4 平台侧

- 在 SonarCloud 创建 Quality Gate（命名如 `YL-M2-Gate`），按 §3 配置条件并按阶段启用；
- 将 `YL-M2-Gate` 设为 `yl-server` 的默认 Gate；
- 打开 **New Code 定义 = Previous Version**。

---

## 6 与既有构建内门禁的关系（不重复，互为补充）

| 门禁 | 层次 | 职责 | 与 Sonar 关系 |
|---|---|---|---|
| Spotless | 构建内 | 代码格式（AOSP） | 互补；Sonar 不管格式 |
| Checkstyle | 构建内 | 代码风格（error 级阻断） | 互补；Sonar 规则集更广（可维护性/复杂度） |
| SpotBugs | 构建内 | 字节码缺陷（Max/Medium） | **有重叠**：Sonar 的 Bug 规则与 SpotBugs 部分重合 → **以 SpotBugs 即时阻断为主，Sonar 覆盖新代码趋势** |
| Jacoco | 构建内 | 覆盖率数据 | Sonar **消费** Jacoco 报告，不重复采集 |
| **SonarQube QG** | **平台侧** | 汇聚 + 趋势 + 安全热点 | 汇总上述结果，补足安全与增量治理 |

---

## 7 待评审确认项

| # | 待确认 | 本提案取值 | 备选 |
|---|---|---|---|
| 1 | 新代码窗口 | Previous Version | 固定日期基线（M2 开工日 2026-09-18） |
| 2 | 新代码覆盖率阈值 | M3 起 ≥ 60% | ≥ 50%（更宽松）/ ≥ 70%（更严） |
| 3 | 全量覆盖率目标 | M3 ≥ 40%、M4 ≥ 60% | 统一 ≥ 50% |
| 4 | 是否启用 `qualitygate.wait`（红灯阻断 CI） | **启用** | 先 WARN 观察一迭代再启用 |
| 5 | SonarCloud vs 自建 SonarQube | SonarCloud（已配 org） | 自建（内网、数据不出域，合规友好）※ |
| 6 | 核心模块是否单设更严阈值 | 是（`yl-domain`/分级引擎 ≥ 80%） | 全模块统一 |

> ※ **合规提示**：本项目涉敏感个人信息，若安全要求"源代码不出域"，则 SonarCloud（SaaS）不可用，须改**自建 SonarQube**（内网部署）。此点建议 DPO/安全侧一并确认。

---

## 8 定稿决议（2026-09-21）

需求方授权按推荐口径定稿，§7 六项待确认**逐项闭环**：

| # | 待确认项 | **定稿口径** | 理由 |
|---|---|---|---|
| 1 | 新代码窗口 | **Previous Version**（每次发版打 tag；M2 内未发版时以 M2 开工日 2026-09-18 为基线） | 与「增量不劣化」目标一致；固定日期窗口会随时间漂移、噪声增大 |
| 2 | 新代码覆盖率阈值 | **M3 起 ≥ 60%** | 新代码可控、可达；低于 60% 无法反映回归保护强度 |
| 3 | 全量覆盖率目标 | **M3 ≥ 40%、M4 ≥ 60%** | 存量补齐需要时间，分阶段避免一次性全红 |
| 4 | 是否启用 `qualitygate.wait`（红灯阻断 CI） | **启用**（加 `-Dsonar.qualitygate.wait=true`） | 当前「扫而不管」等于无门禁；本项与 ADR-0001 的「配置存在 ≠ 门禁生效」教训直接对应 |
| 5 | SonarCloud vs 自建 SonarQube | **自建 SonarQube（内网部署）** | 本项目涉敏感个人信息，**源代码不出域**要求下 SaaS 不可用；合规优先于运维便利 |
| 6 | 核心模块是否更严 | **是**：`yl-domain` + 分级引擎模块新代码 **≥ 80%** | 分级判定是国标合规核心，须最高覆盖强度 |

**补充定稿（P0 即时生效项，与 §3.1 一致）**：新代码 **0 Bug / 0 漏洞 / 安全热点复核 100% / 可维护性 ≥ A / 重复率 ≤ 3%**。

### 8.1 批准后落地清单（按序执行）

| # | 动作 | 产出 |
|---|---|---|
| 1 | Jacoco 绑 `verify` | `pom.xml` 加 `<executions>`（prepare-agent + report），修正「悬空」 |
| 2 | 平台部署 | 内网自建 SonarQube + `YL-M2-Gate` Quality Gate（按 §3 阈值配置） |
| 3 | 项目配置 | `sonar-project.properties`：`sonar.host.url`（内网）、`sonar.coverage.jacoco.xmlReportPaths`、排除 `target/**` |
| 4 | CI 接线 | `ci.yml` 加 `-Dsonar.qualitygate.wait=true`，**红灯阻断合并 main** |
| 5 | 测试补齐 | M3 开工前补 `src/test`（当前 **0 个测试类**，覆盖率门禁在此之前无法生效） |
| 6 | 门禁台账 | 更新 `docs/ADR/0001-static-analysis-gates.md`：登记 Sonar 为第 4 道构建外门禁 |

> **⚠ 定稿的前置事实提醒**：当前仓库 **0 个测试类**、Jacoco **从未执行**。
> 因此第 2/3 项（覆盖率阈值）在 **M3 补齐测试前不会生效**——这是**有意设计**，
> 避免在无测试基线上直接亮红灯导致 CI 长期红态、门禁被绕过（`--no-verify` 式失效）。

---

**编制**：研发（虚拟-后端开发）｜**关联**：`docs/ADR/0001-static-analysis-gates.md`、`.github/workflows/ci.yml`、`pom.xml`
