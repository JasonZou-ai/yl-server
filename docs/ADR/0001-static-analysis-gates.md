# ADR-0001 静态分析门禁：Spotless + Checkstyle + SpotBugs

- 状态：已采纳
- 日期：2026-09-18
- 关联任务：B1-1 技术选型与工程脚手架（含子任务 r0wpMZ 静态检查与 CI）、B1-2 统一 API 规范与网关

## 背景

B1-1 需要一套可在 CI 上稳定复现的质量门禁。M2 起代码量大、并行开发，若门禁只在文档里规定、不落到构建生命周期上，
就会退化成"摆设"——本次就实际发生了：SpotBugs 只声明在 `pluginManagement` 中，未绑定到 `<build><plugins>`，
`mvn verify` 从未执行过它，直到手工调用才发现问题。

## 决策

1. **三个闸门全部绑定 `mvn verify`**，本地与 CI 行为完全一致。
   - Spotless（google-java-format AOSP）：绑定 `compile` 阶段 `check`
   - Checkstyle：绑定 `verify` 阶段 `check`
   - SpotBugs：绑定 `verify` 阶段 `check`
   - 一次 `mvn verify` 完成全部校验，避免 CI 里"手工拼命令"与本地不一致。
2. **SpotBugs 强度**：`effort=Max`、`threshold=Medium`、`failOnError=true`。Medium 及以上阻断构建。
   新项目历史包袱为零，先在早期把标准立高，比后期补债成本低。
3. **版本统一管理**：`spotbugs.version` 在父 POM 属性中声明；CI **不再硬编码插件版本**
   （原先硬编码 `4.10.4.1` 与 POM 中声明的 `4.8.3.4` 不一致，后者在公共仓库根本不存在）。
4. **豁免机制分级，且"能修就不压"**：
   - Checkstyle 用源码内注释 `// CHECKSTYLE_OFF: <CheckName>`，随代码走。
   - SpotBugs 用 `@SuppressFBWarnings(justification = "...")`，`justification` 必填。
   - 依赖注解包 `com.github.spotbugs:spotbugs-annotations`，scope=`provided`（仅编译期，不进产物）。
5. **行尾统一 LF**：`<lineEndings>UNIX</lineEndings>` + `.gitattributes`（`* text=auto eol=lf`），
   消除 Windows(CRLF) 开发机与 Linux CI(LF) 的判定分歧。

## 落地时被门禁抓出的真实缺陷

| 缺陷 | 位置 | 处置 |
|---|---|---|
| `EI_EXPOSE_REP`：分页结果返回可变内部集合 | `PageResult.getList()` | **真修**：构造时 `List.copyOf` 快照，getter 返回不可变视图 |
| `EI_EXPOSE_REP` / `EI_EXPOSE_REP2`：鉴权主体角色集合可被外部改写 | `LoginUser` | **真修**：紧凑构造器拷贝 + 显式访问器返回不可变视图 |
| `HRS_REQUEST_PARAMETER_TO_HTTP_HEADER`：请求头原样回写响应头 → CRLF 头注入 | `TraceIdFilter` | **真修**：`X-Trace-Id` 全量白名单校验（`[A-Za-z0-9_-]{8,64}`），非法即改用服务端 UUID |
| `CT_CONSTRUCTOR_THROW`：构造器抛异常，对象半初始化可被 finalizer 攻击 | `JwtTokenProvider` | **真修**：类声明为 `final` |
| `EI_EXPOSE_REP2`：注入 Spring 管理的线程安全共享 Bean | 4 处 `@RequiredArgsConstructor` | **豁免**（DI 标准用法，附 justification） |

> 注：`TraceIdFilter` 在补上白名单校验后，SpotBugs 反过来报 `US_USELESS_SUPPRESSION_ON_METHOD`——
> 说明真实修复到位后豁免已无必要，故**最终代码中不含该豁免**。这正是"能修就不压"的价值。

## 后果

- 正面：门禁可信，缺陷在提交前暴露；CI 与本地一致，避免"本地过、CI 挂"。
- 代价：`mvn verify` 时长增加（本机 14 模块全量约 29s）；SpotBugs Medium 阈值可能在引入
  第三方库适配代码时产生较多 DI 类误报，需要按上述约定逐个甄别而非批量压制。
- 应急通道：`mvn -Pfast verify` 可临时跳过 Checkstyle 与 SpotBugs，**仅限本地排查**，CI 不使用。
