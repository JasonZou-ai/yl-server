# 端侧对接 `org:contact:read` · 改造点清单（B-1-a 端侧部分）

> 编制：Jason ｜ 2026-10-15 ｜ 授权：PM 会签 Q-B ✅（**端侧为独立授权，不继承后端授权**）
> 后端已交付：`GET /api/v1/orgs/current/contact`（零参数，五角色可读，只下发 **机构名 / 联系人 / 地址**，**不下发机构电话**）

---

## 〇、目标与边界

| 项 | 内容 |
|---|---|
| 目标 | 消除 `rqZ0a3`-B5 的**占位文案「以机构实际公告为准」**（止血前为虚假热线 `400-000-0000`） |
| 下发字段 | `orgName`（机构名）/`contactName`（照护顾问）/`address`（地址） |
| **不下发** | 机构电话（密文列 `contactPhoneEnc`，`OrgView` 刻意不返回；明文方案 a1/a2 本次搁置，需单独授权 + DPO 复核） |
| 兜底 | 接口失败 / 空值 ⇒ **继续显示占位文案**，不得回退成任何硬编码号码 |

---

## 一、改造点（grep 定位式，**禁止按行号改**）

### 1) uni-app 三端（weixin-app / ios-app / douyin-app）

**主改造点**：`src/pages/family-care/family-care.vue`

```bash
# 定位常量定义与渲染（三端各自执行）
grep -rn "ORG_NAME" <端>/src/pages/family-care/family-care.vue
```

当前形态（三端一致）：

- 常量区：`const ORG_NAME = ''` 及相邻的 `ORG_HOTLINE` / `ORG_ADVISOR`（**全为空串**）
- 渲染区：三行 `{{ ORG_NAME || '以机构实际公告为准' }}` / `{{ ORG_HOTLINE || '暂未配置，请以机构实际公告为准' }}` / `{{ ORG_ADVISOR || '以机构实际公告为准' }}`

**改法**：常量改为响应式变量（如 `ref('')`），页面加载时调后端端点填充 `orgName/contactName/address`；**`ORG_HOTLINE` 仍留空 + 占位文案不变**（本轮无明文热线）。

### 2) 次要改造点（同一占位文案散落处）

```bash
grep -rn "以机构实际公告为准" <端>/src --include=*.vue --include=*.ts
```

命中范围（实测）：`pages/family-care/family-care.vue`、`pages/login/login.vue`（联系客服）、`pages/protocol/protocol.vue`。

> 建议：**本轮只改 family-care 主页面**（咨询入口），login/protocol 属文案类，可留 backlog，避免扩散面过大。

### 3) android-native

```bash
grep -rn "以机构实际公告为准" <android-native>/app/src/main/java
```

实测命中：`feature/AgreementScreen.kt`（隐私政策「联系我们」段）。**Kotlin 端本轮建议不改**（页面语义是"客服热线"，而本轮不下发热线 ⇒ 改了也仍是占位）；登记为 backlog。

---

## 二、实施约束（红线）

1. 🔴 **三端 md5 同步**：`weixin-app` 改一份 → **md5 同步 ios-app / douyin-app**（本项目既定规矩，防四端漂移）。
2. 🔴 **禁止引入硬编码 PII / 号码**：不得出现任何 `1[3-9][0-9]{9}` 手机号或 `400-*` 号码字面量。
3. 🔴 **产物级验收（不看源码写法）**：

```bash
grep -rn "内测\|facePassed\|400-000-0000" <端>/dist/build/h5/ | grep -c .   # 期望 0
grep -rnoE "1[3-9][0-9]{9}" <端>/dist/build/h5/ | head                      # 期望 0 命中
```

4. 🟠 **失败兜底**：接口非 0 / 网络异常 ⇒ 静默回落占位文案，**不得弹错、不得阻塞页面**。
5. 🟠 **E2E 跑的是 dist 不是 src**：改完必须**重建产物**再验（重建前先重命名 `dist/build/h5`，sandbox 会拦 Vite 清空 outDir）。

---

## 三、验收口径（并入 `rqZ0a3` 材料）

| # | 判据 | 期望 |
|---|------|------|
| 1 | 老人 / 家属角色进入照护页 | 显示**真实机构名 / 照护顾问 / 地址**（dev 库机构为「联调演示机构」） |
| 2 | 咨询电话一栏 | 仍为占位文案（本轮不下发热线），**无虚假号码** |
| 3 | 未登录 / 令牌失效 | 回落占位文案，页面不报错 |
| 4 | 产物级扫描 | `400-000-0000` / 手机号正则 / `facePassed` **均 0 命中** |
| 5 | 三端 md5 | weixin/ios/douyin 三端 `family-care.vue` **md5 一致** |

> ⚠ 验收结论由**验收人**填写，实施方不得代签。

---

## 四、待确认（实施范围）

| 选项 | 范围 | 代价 |
|---|---|---|
| ① 最小面（推荐） | 只改三端 uni-app 的 `family-care.vue` | 面小、可 revert、直击 B5 |
| ② 全文案面 | 再加 login.vue / protocol.vue | 扩散面大，文案类收益低 |
| ③ 含 Kotlin 端 | 再加 android-native | 本轮无热线可显示，改了仍占位 ⇒ 不建议 |

> ⚠ 仅为模板，复制后务必本地复测，严禁直接粘贴到生产环境执行。
> ⚠ AI 推演辅助输出，文档/代码仅供参考；正式会签、代码落地、上线、回滚必须由研发/PM/测试/合规真人复核执行。
