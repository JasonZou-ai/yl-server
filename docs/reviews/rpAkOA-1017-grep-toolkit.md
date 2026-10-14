# rpAkOA · 10-17 收口｜只读检索工具单（grep 定位式 · 禁硬编码行号）

> 文档编号：**YL-RV-RPAKOA-GREPKIT** ｜ 编制：Jason（材料整理 / 只读复核）｜ 日期：2026-10-14
> 对应：`docs/reviews/rpAkOA-1017-closeout-runbook.md`（收口执行单）
> **性质：全篇只读命令，不写库、不改文件、不删任何东西。** 判据一律「grep 定位 → 人工确认 → 再动手」。
> ⚠ AI 推演辅助输出，文档/代码仅供参考；正式会签、代码落地、上线、回滚必须由研发/PM/测试/合规真人复核执行。

---

## 0. 为什么单独出这份

收口执行单里每一步都要「先定位再改」，但**行号会漂**（上游插入一行，写死的行号就指错地方）。本单把所有定位动作统一为 **grep 定位式**，执行者复制即用；命中为 0 时按「假阴排查三问」处理，不许直接下结论。

---

## 1. 本轮实测复核（2026-10-14 13:5x，全为只读）

| # | 复核项 | 检索式 | 实测结果 | 判定 |
|---|---|---|---|---|
| 1 | PRD 正文是否已并入回写稿 | `grep -c '〔补〕' docs/prd/PRD_YL-PRD-v1.2.md` | **0** | 🔴 未并入 |
| 2 | 权限码列是否落地 | `grep -n 'edit:suggest' docs/prd/PRD_YL-PRD-v1.2.md` | **0 命中** | 🔴 未并入 |
| 3 | 权限点 ID 是否落地 | `grep -c '2008' docs/prd/PRD_YL-PRD-v1.2.md` | **0** | 🔴 未并入（`2008` 仅存在于种子/清单） |
| 4 | `perm_code` 命中性质 | `grep -n 'perm_code' docs/prd/PRD_YL-PRD-v1.2.md` | **1 命中，落在文首横幅描述语** | 🟠 描述语命中 ≠ 正文已回写 |
| 5 | `data_scope` 命中性质 | `grep -n 'data_scope' docs/prd/PRD_YL-PRD-v1.2.md` | **1 命中，同样在横幅描述语** | 🟠 同上 |
| 6 | 权威文件基线是否漂移 | `md5sum docs/prd/PRD_YL-PRD-v1.2.md` | `3c416935916e31e06c3f91d3bf26f076`／23,875B，与 `docs/prd/README.md` 登记**一致** | 🟢 未漂移 |
| 7 | 回写稿载体在位 | `ls docs/prd/addendum/` | `CR-M2-001-permission-matrix-gap.md`（9,005B） | 🟢 在位 |

**结论**：会签文档事项 2 记为「已回写」**不成立**（第 3 次独立复核，结论稳定）⇒ 10-17 收口**必须**含「PRD §2.2 实质回写」这一步，只撤横幅 = 治理痕迹写已追认、正文仍是追认前旧口径，自相矛盾。

---

## 2. 本轮新发现的两处文档内部矛盾（收口时一并订正）

| # | 位置 | 矛盾描述 | 风险 | 建议处置 |
|---|---|---|---|---|
| M-1 | `docs/prd/addendum/CR-M2-001-permission-matrix-gap.md` 抬头第 4 行 | 写「对应主文档：**YL-PRD-v1.1**（`PRD_YL-PRD-v1.1.md`）」，但 `docs/prd/` 下**已无 v1.1 文件**（v1.1 已移至 `archive/`，权威位是 v1.2）⇒ 相对链接指向不存在的文件 | 🟠 并入后读者按补遗路径找不到权威件；并入动作本身会引用错对象 | 收口步骤 2 同步把抬头改为 `../PRD_YL-PRD-v1.2.md`，并把「对应主文档」版本号改 v1.2 |
| M-2 | 同文件第 5 行状态栏 | 仍写「⏳ 待 CCB 追认（限 2026-10-17）」，与**已完成的五方会签结论**不同步 | 🟡 状态双轨：会签文档说已通过、补遗件说待追认 | 会签结论落定后同步改为「✅ 已追认（YYYY-MM-DD）· 待并入正文」，并入后再置「已归档」 |

---

## 3. grep 检索模板（按场景复制即用）

### 3.1 PRD 实质回写验收（步骤 2 完成后跑）

```bash
cd docs/prd
# ① 6 行功能已补（期望 ≥6）
grep -c '〔补〕' PRD_YL-PRD-v1.2.md
# ② perm_code 列已落地（期望 ≥2，且至少 1 次不在横幅行）
grep -n 'perm_code' PRD_YL-PRD-v1.2.md
# ③ 关键权限码真的是完整串（不是 `…:edit:suggest` 省略式）
grep -n 'care:plan:edit' PRD_YL-PRD-v1.2.md
# ④ 权限点 ID 已登记（期望 ≥1）
grep -n '2008' PRD_YL-PRD-v1.2.md
# ⑤ data_scope 口径已落地（期望 ≥2，且含五角色取值）
grep -n 'data_scope' PRD_YL-PRD-v1.2.md
# ⑥ 横幅已撤（期望 0）
grep -c '尚未并入正文' PRD_YL-PRD-v1.2.md
# ⑦ md5 连锁必须同步更新到 README
md5sum PRD_YL-PRD-v1.2.md addendum/CR-M2-001-permission-matrix-gap.md archive/PRD_YL-PRD-v1.1.md
```

> **假阴排查三问**（命中 0 时不许直接判「没做」）：
> ① 通配是否展开？——全仓检索用 `.` 作根并 `grep -v /target/`，`yl-*/src/main` 会漏掉 `yl-modules/*`；
> ② 是不是省略写法？——补遗件用 `…:edit:suggest`，搜完整串必然 0；
> ③ 命中是不是描述语？——横幅/说明里的词不算落地，要看**是否出现在矩阵表格行内**。

### 3.2 端侧 B1-B5 产物级验收（rqZ0a3 补丁后跑，扫**构建产物**不是源码）

```bash
# B1 人脸门禁：源码不得残留无条件赋值
grep -rn 'facePassed' <端侧目录>/src/ | grep -v node_modules
# B2 硬编码 PII：手机号正则（期望 0）
grep -rnE '1[3-9][0-9]{9}' <端侧目录>/dist/build/h5/ | grep -v node_modules
# B4 构建隔离：生产包不得出现演示入口（期望 0）
grep -rn '内测' <端侧目录>/dist/build/h5/
# B3/B5 虚假联系方式（期望 0）
grep -rn '400-000-0000\|0871-0000' <端侧目录>/dist/build/h5/
# 三端 md5 对齐（改动必须四端同源）
md5sum <weixin>/src/... <douyin>/src/... <ios>/src/... <android>/src/...
```

### 3.3 后端权限 / 门禁基线复核（只读）

```bash
cd yl-server
# 授权条数（当前基线 41）
grep -rn 'care:plan:edit:suggest' --include=*.sql . | grep -v /target/
# 敏感点四码是否仍为 4 个
grep -rn "data:export\|evaluation:order:void\|account:family:unbind\|data:reveal" --include=*.sql . | grep -v /target/
# 契约操作数与漂移（期望 8/8）
bash scripts/verify-openapi.sh
# schema 表数（期望 86）
bash scripts/verify-schema.sh
```

---

## 4. 责任矩阵（10-17 倒计时 3 天）

| 卡片 | 标的 | 板块负责人 | 风险 | 授权状态 | 截止 |
|---|---|---|---|:--:|:--:|
| `rpAkOA` | 10-17 收口（含新增步骤 2 实质回写） | PM 签发 | 🔴 | ⏸ **待授权** | **10-17** |
| `rqZ0a3` | 端侧止血 B1-B5 | 四端研发 + 合规 DPO | 🔴 | ⏸ **待授权** | P0 止血 |
| `rR1a6L` | 2008 写权限开放前置（ELDER + 路由兜底） | 后端研发 + 测试负责人 | 🔴 | ⏸ **待授权** | 2008 冻结中 |
| — | Bot Secret 轮换 | 合规 DPO | 🟠 | ⏸ **待授权** | 尽快 |
| — | 网盘一致性 A/B 拍板 | PM | 🟡 | ⏸ 待拍板 | 10-17 前 |
| — | 两条 IM 简报人工转发（会话数为 0，无法直发群） | 你本人 | 🟡 | 人工 | 即刻 |
| — | 自动回复真实消息触发验证（群内再 @ 一次） | 你本人 | 🟡 | 人工 | 即刻 |

---

## 5. 红线复述（改任何一项前先读）

1. 🔴 **两套开关严格隔离**：读路径状态闸可先行上线；2008 写权限须多条件集齐才开放。
2. 🔴 **高危回滚红线**：**禁止回退授权**（回退 `eval_report` 归属锚同时保留 2008 授权 = 写越权漏洞）；不对称回滚只能「先撤授权、再回退锚」。
3. 🟠 **M3 状态闸边界**：机构管理员**仍可读** `status=2` 作废报告，不可改成全局拦截。
4. 🟡 本单所有命令**均为只读检索**，不改任何文件；据此生成的补丁/回写/回滚脚本属高风险，须另行授权并以 diff 呈现。

> ⚠ AI 推演辅助输出，文档/代码仅供参考；正式会签、代码落地、上线、回滚必须由研发/PM/测试/合规真人复核执行。
