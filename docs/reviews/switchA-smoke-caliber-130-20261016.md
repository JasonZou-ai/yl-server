# 开关 A 冒烟「130 vs 127」口径订正 · 2026-10-16

> 性质：**口径核对 + 门禁补强**（非验收结论、非业务代码改动）
> 触发：2026-10-16 交付包 README 自报「巡检 **127 通过 / 0 失败 / 1 跳过**，与文档 **130** 口径有差，以实跑为准」
> 结论：**差值已 100% 定位，非基线回退；修正后实跑 = 130 / 0 / 0**
> 基线：main = `27bffe5`（**未改动**）；本轮改动落在分支 `feature/switchA-patrol-export-dir`

---

## 一、TL;DR

| 项 | 结果 |
|---|---|
| 127 vs 130 的差 2 条去哪了 | **被「一条 skip」折叠掉了**：缺 `EXPORT_DIR` 时，B9 的 **3 条**断言（CSV 产物魔数 / CSV 首行表头 / CSV 公式注入转义）在脚本里是一个 `if/else` 分支，**else 只记 1 条 skip** ⇒ 计数变成 127+1=128 |
| 是不是基线回退 | **不是**。后端行为未变、断言未删；但**每日门禁实际少验 2 条**，属「隐形降基线」，必须补 |
| 补法 | 巡检脚本**自动推导并强制** `EXPORT_DIR`（`yl.export.dir` 留空时回退 `${java.io.tmpdir}/yl-exports`） |
| 补后实跑 | ✅ **130 通过 / 0 失败 / 0 跳过**（2026-10-16 14:03，日志 `/tmp/d4-run/patrol/switchA-smoke-20261016-140329.log`） |
| 附带发现并修复 | 编码 shim 硬编码 `/mingw64/bin/curl`，在本机被解析到**另一个 mingw64 根（无 curl）** ⇒ 登录失败 22 条假红 ⇒ 巡检**假失败**。已改自动探测并入库 |

---

## 二、差的 2 条：逐行定位

`scripts/smoke-report-review-chain.sh` **L637–661**：

```sh
637  if [ -n "$EXP_DIR" ] && [ -n "$B9_TASKNO" ] && [ -f "$EXP_DIR/export/$B9_TASKNO.csv" ]; then
639      check "B9 CSV 产物魔数（UTF-8 BOM efbbbf）" ...      # 断言 ①
644      pass  "B9 CSV 首行表头（评估单号,能力等级,老人姓名,...）"  # 断言 ②
652      pass  "B9 CSV 公式注入转义（单元格以 ' 前缀，§2.4）"      # 断言 ③
660    skip "B9 CSV 魔数/表头/注入转义（需设 EXPORT_DIR …）"        # ← else：3 条折叠成 1 条
```

| 环境变量齐备度 | 通过 | 失败 | 跳过 | 有效断言总数 |
|---|---:|---:|---:|---:|
| **全齐备**（含 `EXPORT_DIR`） | 130 | 0 | 0 | **130** ← 文档基线 |
| 缺 `EXPORT_DIR`（10-16 首跑） | 127 | 0 | 1 | **128**（少 2 条，**无提示**） |

> 另核：脚本头注 **124**（2026-10-13 实跑校准，commit `d943992`）+ **6** 条（2026-10-15 rR1a6L B5-后端「本人报告列表」新增）= **130**，与本次实跑**互证一致**，无第二处口径错。

---

## 三、第二坑：编码 shim 硬编码 curl 路径（会导致巡检**假失败**）

| 现象 | 14:01 首跑 `✗ 联调账号登录失败`，退出码 1 |
|---|---|
| 真因 | `shim` 内 `REAL=/mingw64/bin/curl`；本机 `/mingw64/bin` **存在但属另一个 mingw64 根**（无 curl）⇒ `No such file or directory` |
| 定性 | **环境/客户端问题**，与后端、鉴权、密钥**无关** —— 与项目既有教训「先探活再判因」一致，勿误判为 10999 权限故障 |
| 修法 | 改为按序探测：`REAL_CURL` → `/mingw64/bin/curl` → `C:/Program Files/Git/mingw64/bin/curl.exe` → PATH；全无则 **exit 127（fail-closed）** |
| 落地 | 修正件**入库** `scripts/dev/curl-utf8-shim.sh`（原先只躺在 `/tmp/d4-run/shim`，不可复现、不可审计） |

---

## 四、本轮改动（全在分支，main 未动）

| 文件 | 动作 | 说明 |
|---|---|---|
| `scripts/patrol-switchA-daily.sh` | **新增入库** | 原先只在交付包里，未版本化；现含：① `MYSQL_BIN` 自动探测 ② **`EXPORT_DIR` 自动推导 + 强制**（目录不可读即 fail-closed）③ **「有效断言总数 != 130」判失败**自检 —— 专防「折叠 skip」造成的隐形降基线 |
| `scripts/dev/curl-utf8-shim.sh` | **新增入库** | 修正后的编码 shim（curl 路径自动探测） |
| `docs/reviews/switchA-smoke-caliber-130-20261016.md` | 新增 | 本文 |

> ⚠ **未合并 main 的理由**：验收清单 README 明示「`git rev-parse HEAD` 期望 **27bffe5**」，
> 测试负责人据此复现；合并会让 HEAD 漂移、破坏其复现前提。**待 10-20 验收结论出具后（或 10-21 起）再并入。**

---

## 五、副作用与风险

- 巡检脚本会**真实读写 dev 库**（冒烟固有行为，脚本自带前清/后清与幂等设计）；本次实跑后 `剩余业务数据：评估单 0 行`，无残留。
- 新增强制 `EXPORT_DIR` 后，若后端导出目录被清理或迁移，巡检将 **fail-closed 报错**而非降级静默 —— 这是**刻意设计**（宁可告警不可假绿）。
- 未触碰：RBAC 种子、权限配置、开关 B、验收清单 12 个勾选框。

---

## 六、复现命令

```bash
cd C:/Users/Administrator/WorkBuddy/2026-09-18-11-08-33/stage3_开发/yl-server
git rev-parse --short HEAD          # 27bffe5（基线未动）
bash scripts/patrol-switchA-daily.sh /tmp/d4-run/patrol
# 期望：[patrol] 口径：通过=130 失败=0 跳过=0 有效断言总数=130（期望 130）
#       [patrol] PASS：读路径冒烟 130/130 全绿     退出码 0
```

---

> ⚠ 本文为 AI 辅助整理：**只给证据与复现路径**，不签发验收结论、不执行上线与回滚；正式动作须真人复核。
