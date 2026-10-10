# CR-M2-003：保留字列名映射缺陷——审计留痕长期 0 行

| 项 | 内容 |
|---|---|
| 编号 | **CR-M2-003** |
| 类型 | 缺陷（合规相关） |
| 发现时间 | 2026-09-23 |
| 发现场景 | P1-4b 归档链路真实库验收：链路跑通后日志出现 `链路已完成但审计留痕失败` |
| 严重度 | **高** —— 审计留痕是敏感操作（作废/导出/解绑）的合规凭证，静默失效等同于"无留痕" |
| 状态 | ✅ 已修复（含系统性扫描 + 回归测试 + 门禁金丝雀） |

## 1 现象与证据

在真实 MySQL 8.0.46 上运行保留期链路（`RetentionChainScheduler`）后：

```
ERROR [scheduling-1] c.y.b.s.RetentionChainScheduler - [retention] 链路已完成但审计留痕失败：purged=0 archived=1
org.springframework.jdbc.BadSqlGrammarException:
### Error updating database.  Cause: java.sql.SQLSyntaxErrorException: You have an error in your SQL syntax ...
### The error may exist in cn/yl/modules/account/mapper/AuditLogMapper.java
### SQL: INSERT INTO audit_log  ( id, action, biz_type,      sensitive, second_verify, detail, retain_until )  VALUES ( ?, ?, ?,      ?, ?, ?, ?  )
### Cause: ... near 'sensitive, ' ; bad SQL grammar []
    at cn.yl.modules.account.service.AuditService.record(AuditService.java:75)
```

旁证：真实库 `SELECT COUNT(*) FROM audit_log` = **0 行**——即**审计留痕从未成功落库**，而非"只有本次失败"。

## 2 根因

- MySQL 8 有 **262 个保留字**（`information_schema.KEYWORDS.RESERVED = 1`），`SENSITIVE` 在其中；
- 实体 `AuditLog.sensitive` **未加反引号映射**，MyBatis-Plus 据此生成 `INSERT INTO audit_log (..., sensitive, ...)`；
- MySQL 在解析阶段即判语法错误 → **整条 INSERT 失败**（不是"这一列写不进去"，而是整行写不进去）。

## 3 为什么此前一直没暴露

| 环节 | 为何漏过 |
|---|---|
| 编译 | 是 SQL 字符串问题，编译期无从发现 |
| 单测 | `AuditLogMapper` 被 Mock 掉，`record()` 的写入路径从未真正执行 |
| schema 门禁 | 断言的是 **DDL 结构**（列是否存在、约束是否生效），不覆盖"实体映射能否生成合法 SQL" |
| 调用方 | `AuditService.record` 失败被调用方 **catch 后只记 error**（设计如此：留痕失败不影响主流程）→ 业务无感 |

正是"留痕失败不阻断业务"的合理设计，掩盖了"留痕从未成功"的事实。

## 4 影响面

所有经 `AuditService.record` 的留痕，包括但不限于：

- ER-14 三方绑定到期物理删除（`THIRD_PARTY_PURGE`）；
- 本次新增的保留期链路（`RETENTION_CHAIN`）；
- 敏感操作留痕（作废 `evaluation:order:void`、导出 `data:export`、解绑 `account:family:unbind`、`data:reveal`）。

## 5 修复

```java
// AuditLog.java
@TableField("`sensitive`")
private Integer sensitive;
```

### 5.1 系统性扫描口径（勿凭记忆逐个猜）

```sql
SELECT c.table_name, c.column_name
  FROM information_schema.columns c
  JOIN information_schema.KEYWORDS k ON UPPER(c.column_name) = k.WORD AND k.RESERVED = 1
 WHERE c.table_schema = 'yl_evaluation';
```

**实测结果：全库仅 1 处**（`audit_log.sensitive`），故为单点修复，无其他同源缺陷。

### 5.2 三道防线（防止复发）

1. **回归测试** `ReservedWordColumnMappingTest`：对清单内每个列断言存在 `@TableField("`col`")`；
2. **门禁金丝雀** `verify-schema.sh`：断言"列名与保留字冲突数 = 1"——新增命中列时门禁变红，强制核对实体映射；
3. **扫描口径写进测试与门禁注释**，避免下一个人重新踩。

## 6 验收

| 项 | 口径 |
|---|---|
| 修复生效 | 真实库上跑一次链路 → `audit_log` 出现 `RETENTION_CHAIN` 行，且 `sensitive=0`、`detail` 含两个计数 |
| 无回归 | `ReservedWordColumnMappingTest` 绿；`verify-schema.sh` 78/78 绿 |
| 连带修复 | ER-14 `THIRD_PARTY_PURGE` 留痕同时恢复（同一写入路径） |

## 7 后续建议（不在本次范围）

- `AuditService.record` 的失败目前只有 error 日志，建议补**告警**（连续 N 次失败 → 通知）。因为"留痕静默失效"本身不会有人看到；
- 建议在 M2-S3（任务集 D）里把审计写入纳入真实库冒烟脚本的固定断言，避免再靠"顺手发现"。
