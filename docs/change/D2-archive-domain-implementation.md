# D-2 档案域后端 · 实施记录

> 角色：工程师（寇豆码）｜依据：`docs/design/D2-archive-domain-design.md`（冻结版）
> 范围：`yl-server` 档案域 4 端点翻旗 + CR-M2-007 未认证修复｜日期：2026-09-26
> 结论：**全绿**（`mvn -o verify` BUILD SUCCESS；`verify-openapi.sh` 8/8；`verify-schema.sh` 82/82；真实 HTTP 冒烟 53 通过 / 0 失败 / 0 跳过）。

---

## 一、改动文件全清单

### 新增（21 个）

**档案域代码（`yl-modules/yl-module-archive`，13 个）**

| # | 相对路径 |
|---|---|
| 1 | `src/main/java/cn/yl/modules/archive/domain/entity/Elder.java` |
| 2 | `src/main/java/cn/yl/modules/archive/mapper/ElderMapper.java` |
| 3 | `src/main/java/cn/yl/modules/archive/support/SensitiveFieldSupport.java` |
| 4 | `src/main/java/cn/yl/modules/archive/dto/ElderCreateRequest.java` |
| 5 | `src/main/java/cn/yl/modules/archive/dto/ElderView.java` |
| 6 | `src/main/java/cn/yl/modules/archive/dto/ElderQuery.java` |
| 7 | `src/main/java/cn/yl/modules/archive/dto/DataRevealRequest.java` |
| 8 | `src/main/java/cn/yl/modules/archive/dto/DataRevealResponse.java` |
| 9 | `src/main/java/cn/yl/modules/archive/service/ElderNoGenerator.java` |
| 10 | `src/main/java/cn/yl/modules/archive/service/ElderArchiveService.java` |
| 11 | `src/main/java/cn/yl/modules/archive/service/DataRevealService.java` |
| 12 | `src/main/java/cn/yl/modules/archive/controller/ElderController.java` |
| 13 | `src/main/java/cn/yl/modules/archive/controller/DataRevealController.java` |

**单测（3 个）**

| # | 相对路径 |
|---|---|
| 14 | `src/test/java/cn/yl/modules/archive/service/ElderArchiveServiceTest.java` |
| 15 | `src/test/java/cn/yl/modules/archive/service/DataRevealServiceTest.java` |
| 16 | `src/test/java/cn/yl/modules/archive/controller/ElderControllerTest.java` |

**yl-api（2 个，CR-M2-007）**

| # | 相对路径 |
|---|---|
| 17 | `yl-api/src/main/java/cn/yl/api/security/RestAuthenticationEntryPoint.java` |
| 18 | `yl-api/src/main/java/cn/yl/api/security/RestAccessDeniedHandler.java` |

**文档（3 个）**

| # | 相对路径 |
|---|---|
| 19 | `docs/reviews/D2-archive-DDL-Patch-Proposal.sql`（DDL 变更留痕） |
| 20 | `docs/reviews/D2-e2e-checklist.md`（验收清单） |
| 21 | `docs/change/D2-archive-domain-implementation.md`（本文件） |

### 修改（10 个）

| # | 相对路径 | 改动 |
|---|---|---|
| 1 | `yl-modules/yl-module-archive/pom.xml` | 增 `mybatis-plus-spring-boot3-starter` + `spring-boot-starter-test` |
| 2 | `docker/mysql/init/02_schema.sql` | `elder` 内联 `user_id`（L205）+ `uk_elder_user`（L215） |
| 3 | `yl-modules/yl-module-account/src/main/java/cn/yl/modules/account/security/DataScopePermissionHandler.java` | `elder` 移出 `SELF_DENY_TABLES` → `SELF_COLUMN` 增 `("elder","user_id")`（L111）；类 Javadoc 补 D-2 裁决二段（L66-70、L119-120） |
| 4 | `yl-modules/yl-module-account/src/test/java/cn/yl/modules/account/security/DataScopePermissionHandlerTest.java` | 新增 `selfScopeFiltersElderByUserId`（钉 `(elder.user_id = N)`） |
| 5 | `yl-api/src/main/java/cn/yl/api/config/SecurityConfig.java` | 补 `exceptionHandling`（L96-99）+ 注入两个新 Bean + 类 Javadoc（L37,41-44） |
| 6 | `openapi/yl-api.yaml` | 4 操作 `x-status: planned → implemented`（L413/444/487/517 附近）；`ElderCreate.required: [name, orgId] → [name]`；`elderCreate` description 补 `orgId` 口径 |
| 7 | `yl-bootstrap/src/test/java/cn/yl/bootstrap/contract/OpenApiContractTest.java` | `CONTROLLERS` 登记 `ElderController`、`DataRevealController` |
| 8 | `yl-bootstrap/src/test/java/cn/yl/bootstrap/security/ControllerAuthorizationCoverageTest.java` | 同上登记 |
| 9 | `scripts/verify-schema.sh` | 新增 1 条断言（L237）；`EXPECTED_TOTAL_ASSERTIONS 81 → 82`（L478）；头注释同步（L20） |
| 10 | `scripts/smoke-report-review-chain.sh` | 新增 `[7/7] 档案域` 段（L375+）+ 「未携带凭据 → 401 + 10002」断言（L441）+ elder 清理 |
| 11 | `docs/change/CR-M2-007-unauthenticated-status-code-split.md` | 补「裁决记录」段（§八），状态置「已裁决并落地」 |

> 说明：设计 §2.1 修改清单 #27（`CR-M2-007-*.md` 补裁决记录）为纯文档，已一并落地。

---

## 二、两个裁决的落地位置

| 裁决 | 落地位置 |
|---|---|
| **裁决一 · CR-M2-007（未认证统一 401）** | `SecurityConfig.java:96-99`（`exceptionHandling` 装配）；`RestAuthenticationEntryPoint.java`（新增，401+10002）；`RestAccessDeniedHandler.java`（新增，403+10003）；类注释 `SecurityConfig.java:37,41-44` |
| **裁决二 · `elder.user_id` 自见锚列** | `docker/mysql/init/02_schema.sql:205`（列）、`:215`（`uk_elder_user`）；`DataScopePermissionHandler.java:111`（`SELF_COLUMN` 增 `("elder","user_id")`）；类 Javadoc `:66-70`；单测钉死 `DataScopePermissionHandlerTest#selfScopeFiltersElderByUserId` |

**「本体一致性订正」落地**：`openapi/yl-api.yaml` 的 `ElderCreate.required` 改为 `[name]`（该 schema 仅被 `elderCreate` 引用，已 `grep` 确认），并在 `elderCreate.description` 补「`orgId` 可省略，缺省取登录态默认机构；显式传值须落在本人机构域内」。

**`elder_no` 规则落地**：`ElderNoGenerator` Javadoc 明确 `"ELD" + yyyyMMdd + 6 位随机数字`（17 ≤ 32）；唯一性靠 `uk_elder_no` + `ElderArchiveService#insertWithUniqueElderNo` 重试（最多 3 次，仍冲突抛 `CONFLICT=10005`）。

---

## 三、门禁实测输出摘要

### 3.1 `mvn -o verify` → **BUILD SUCCESS**

- Checkstyle：全模块 `0 violations`。
- SpotBugs（effort Max / threshold Medium）：全模块 `No errors/warnings found`。
- 单测：**148 例，0 失败 / 0 错误**（改前 129 → 新增 19：档案域 18 + `DataScopePermissionHandlerTest` 1）。
  - `yl-module-archive`：`ElderControllerTest` 6 + `DataRevealServiceTest` 5 + `ElderArchiveServiceTest` 7 = 18。
  - `yl-module-account`：48（`DataScopePermissionHandlerTest` 16 → 17）。
  - `yl-module-evaluation`：60；`yl-api`：9；`yl-bootstrap`：13。

### 3.2 `bash scripts/verify-openapi.sh` → **通过 8 项，失败 0 项**

```
契约操作总数：43（implemented 26 / planned 17）
[5/5] 漂移：控制器端点数 = 26；契约缺登 = 0；契约多登 = 0
```

### 3.3 `bash scripts/verify-schema.sh` → **通过 82 项，失败 0 项**

```
✓ 档案自见锚列 elder.user_id（bigint，可空） = 1
✓ 列名与 MySQL 保留字冲突数（命中列须反引号映射） = 1
结果：通过 82 项，失败 0 项，待批准守卫 0 项（合计 82 项）
```

---

## 四、真实 HTTP 冒烟实测输出（摘要）

命令：`MYSQL_BIN=/c/mysql/bin/mysql.exe MYSQL_PASSWORD='Mysql8@2026#Dev' SECOND_VERIFY_DEV_CODE=246810 bash scripts/smoke-report-review-chain.sh`
结果：**通过 53 项，失败 0 项，跳过 0 项**。

```
[7/7] 档案域（D-2：建档加密 / 第三闸收口 / reveal 二次验证 + 留痕 / 未认证 401）
  ✓ 建档成功（code=0，elderId=2103676788392116226）
  ✓ 建档返回姓名已脱敏（张*） = 张*
  ✓ 建档返回身份证掩码（前4后4） = 5301**********7264
  ✓ 列表可读本机构档案（code=0） = 0
  ✓ 详情返回脱敏姓名 = 张*
  ✓ reveal 缺二次验证凭据（业务码 10008） = 10008
  ✓ reveal 带令牌返回明文姓名 = 张伟
  ✓ reveal 带令牌返回明文身份证 = 530102199003077264
  ✓ reveal 带令牌返回明文住址 = 云南省昆明市五华区
  ✓ audit_log 留痕（DATA_REVEAL/ELDER/sensitive=1）行数 = 2
  ✓ 库内姓名/身份证为密文且摘要齐备（1 1 1） = 1 1 1
  ✓ 未携带凭据访问受保护端点 HTTP 状态码（CR-M2-007） = 401
  ✓ 未携带凭据响应体业务码 R.code=10002 = 10002
  ✓ 越界/不存在详情（业务码 10004） = 10004
  ✓ 跨机构详情被第三闸拦下（业务码 10004） = 10004
==============================================================
 结果：通过 53 项，失败 0 项，跳过 0 项
==============================================================
  已清理档案域冒烟数据（elder id=2103676788392116226）
```

**关键证据**：
- 建档返回 `name=张*`、`idCardMask=5301**********7264`（脱敏）；
- 库内 `name_enc ≠ 明文`、`id_card_enc ≠ 明文`、`name_hash` 长度 64（**禁明文落库**成立）；
- reveal 缺 `X-Second-Verify-Token` → `10008`；带令牌 → 明文；`audit_log` 有 `sensitive=1` 行；
- **未携带凭据 → HTTP 401 + `R.code=10002`**（CR-M2-007 新口径）；
- 跨机构详情 → `10004`（第三闸按机构收口）。

---

## 五、单测新增清单

| 测试类 | 新增用例 |
|---|---|
| `ElderArchiveServiceTest` | `createEncryptsAndMasksSensitiveFields`、`createRejectsDuplicateIdCard`、`createFailsWhenCodecUnavailable`、`createRetriesElderNoOnConflict`、`listQueriesNonDeletedAndFiltersByKeyword`、`detailReturnsMaskedView`、`detailNotFoundThrows10004` |
| `DataRevealServiceTest` | `revealRejectsUnsupportedBizType`、`revealRejectsUnknownField`、`revealNotFoundWhenRowInvisibleOrMissing`、`revealReturnsPlaintextForWhitelistedFields`、`revealAcceptsLowercaseBizType` |
| `ElderControllerTest` | `createDefaultsOrgToLoginUserOrg`、`createRejectsOrgOutsideDataScope`、`createUsesLoginUserAsOperator`、`listDelegatesToService`、`detailDelegatesToService`、`createRejectedWhenNotAuthenticated` |
| `DataScopePermissionHandlerTest` | `selfScopeFiltersElderByUserId`（scope=1 `elder` → `(elder.user_id = N)`） |

---

## 六、残留项清单

| # | 残留 | 说明 |
|---|---|---|
| **R1** | **`elder↔user` 绑定写入流程未实现（`D-2R1`）** | `elder.user_id` 无生产写入方 → **ELDER 端真机 E2E 不可达**（老人看不到自己档案）。以单测钉死 SQL 语义作为确定性正确性证明；不新增 dev 种子（避免演示无生产入口的路径）。 |
| **R2** | **`dataReveal.bizType=USER` 未支持** | 收到即 `PARAM_INVALID=10001`（"暂不支持"）；属账号域，本期不扩面、不加新权限码。 |
| **R3** | **`audit_log.biz_id` 未填** | `@Audit` 仅透传 `action/biz_type/sensitive`，`biz_id` 保持 NULL；靠 `action=DATA_REVEAL + biz_type=ELDER + sensitive=1` 留痕（满足本期验收）。 |
| **R4** | **孤儿 PDF 清理未做** | 报告 PDF 物理文件清理不在 D-2 范围。 |
| **R5** | `consent_record` 锚为 `subject_type + subject_id`（非 `elder_id`） | 沿用 D-1 残留登记；D-2 四端点不查该表，保持失败即拒的保守落点。 |

---

## 七、与设计文档的不符之处（均为**为实现门禁全绿**的必要调整，已实测确认）

1. **`SensitiveFieldSupport` 的 `@SuppressFBWarnings(EI_EXPOSE_REP2)` 已移除**：设计 §2.1/support 建议加该豁免，但 SpotBugs 实测报
   `US_USELESS_SUPPRESSION_ON_METHOD`（`ObjectProvider` 为接口，不触发 EI_EXPOSE_REP2）。本项目「多余豁免亦阻断」，
   故移除（否则门禁红）。`ElderController` 的同款豁免**予以保留**（实测必要，移除即 EI_EXPOSE_REP2）。
2. **`DataRevealController` 的 `@SuppressFBWarnings` 已移除**：同上，实测为冗余豁免 → 改为裸 `@RequiredArgsConstructor`。
3. **`DataRevealRequest.fields` 加防御性拷贝**：设计未提，但 SpotBugs 报 `EI_EXPOSE_REP/REP2` → `getFields` 返回 `List.copyOf`、
   `setFields` 做 `List.copyOf`（与既有 `GradingRequest` 同范式）。
4. **列表 `keyword` 在编解码器未装配（dev 无密钥）时降级为「仅档案号前缀」**：因 `name_hash` 无法计算。已在 `ElderQuery`/`ElderArchiveService#list`
   的 Javadoc 显式说明（非静默降级，语义在文档中可见）。
5. **`elder_no` 生成避免 `String.format`**：改用零填充拼接，规避默认 locale 相关的静态检查风险；规则不变（`ELD + yyyyMMdd + 6 位`）。
6. **本机 sandbox 会静默丢弃 `env -u ...` 形式的命令**：任务书给的 `env -u SERVER__PORT ... java -jar ...` 在本环境执行会"秒退且无输出"；
   已改用 `unset SERVER__PORT` + `export ...`，并用 `java -cp <jar> org.springframework.boot.loader.launch.JarLauncher` 启动（功能等价）。
   此外 `java -jar <fat-jar>` 在本 sandbox 下表现为立即退出 0、无输出，改为显式 launcher 后正常启动（**已实测**）。
