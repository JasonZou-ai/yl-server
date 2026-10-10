"""保留期链路 · SQL 突变工具（开发工具，非门禁）。

用途：对保留期链路 4 个 retention Mapper 的手写 SQL 做「唯一锚点」字符串替换 / 反向精确还原，
用来人工证明 `scripts/verify-retention-chain.sh`（DB 级门禁）对该条 SQL 条件**有区分力**——
突变后门禁必须变红，还原后必须复绿。

背景（D5-R1）：这 4 条手写 SQL 的 WHERE 条件是「靠 SQL 表达的安全/幂等语义」，mock 单测触不到真实
SQL ⇒ 摘掉它们不会有任何单测变红。因此其回归守卫是 `RetentionChainDbIT`（DB 级），而「该守卫是否真的
能抓住变更」只能靠本工具做突变证明。

★ 本工具**不是门禁**：不参与 `mvn verify`，不参与 CI。真正的门禁是
   `bash scripts/verify-retention-chain.sh`（`-P db-it` 跑 RetentionChainDbIT）。

用法：
    python mutate-retention-sql.py <key> <mutate|revert> [repo_root]
  - key ∈ M1..M5（见下方 PAIRS）
  - repo_root 默认由脚本自身位置推断（<repo>/scripts/dev/ → <repo>）
  输出 `REPLACE_COUNT=n`；n<1 时退出码 2（锚点失配，说明 SQL 已被改动，锚点需同步更新）。

驱动脚本：`scripts/dev/run-retention-mutations.sh`（自动 mutate → 跑门禁 → revert → md5 校验）。
"""

import io
import os
import sys

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
_DEFAULT_REPO = os.path.normpath(os.path.join(_SCRIPT_DIR, "..", ".."))

REPO = sys.argv[3] if len(sys.argv) > 3 else _DEFAULT_REPO

FILES = {
    "A": "yl-modules/yl-module-archive/src/main/java/cn/yl/modules/archive/mapper/ElderFamilyBindMapper.java",
    "S": "yl-modules/yl-module-supervise/src/main/java/cn/yl/modules/supervise/mapper/SuperviseReportMapper.java",
    "E": "yl-modules/yl-module-evaluation/src/main/java/cn/yl/modules/evaluation/mapper/EvalOrderMapper.java",
    "T": "yl-modules/yl-module-account/src/main/java/cn/yl/modules/account/mapper/SysUserThirdPartyMapper.java",
}

# (file_key, mutate_old, mutate_new)；revert = 反向替换。
# M1/M2 打 ④（亲情绑定流转），M3 打 ③（监管上报幂等标志），
# M4 打 ②（ER-03 只归档 PUBLISHED），M5 打 ①（ER-14 物理删除）。
PAIRS = {
    "M1": ("A",
           '"UPDATE elder_family_bind SET status = 4"\n                    + " WHERE status = 2 AND deleted = 0 AND auth_end_at',
           '"UPDATE elder_family_bind SET status = 4"\n                    + " WHERE status = 2 AND auth_end_at'),
    "M2": ("A",
           '"UPDATE elder_family_bind SET status = 4"',
           '"UPDATE elder_family_bind SET status = 4, deleted = 1"'),
    "M3": ("S",
           '+ " WHERE archived_at IS NULL AND retain_until',
           '+ " WHERE retain_until'),
    "M4": ("E",
           '+ " WHERE retain_until IS NOT NULL AND retain_until < #{cutoff} AND status = \'PUBLISHED\'"',
           '+ " WHERE retain_until IS NOT NULL AND retain_until < #{cutoff}"'),
    "M5": ("T",
           '"DELETE FROM sys_user_third_party WHERE retain_until IS NOT NULL AND retain_until',
           '"DELETE FROM sys_user_third_party WHERE retain_until'),
}


def main():
    if len(sys.argv) < 3 or sys.argv[1] not in PAIRS:
        sys.stderr.write("用法：python mutate-retention-sql.py <%s> <mutate|revert> [repo_root]\n"
                         % "|".join(PAIRS))
        sys.exit(64)
    key = sys.argv[1]
    direction = sys.argv[2]
    fkey, old, new = PAIRS[key]
    path = os.path.join(REPO, FILES[fkey])
    if direction == "revert":
        old, new = new, old
    text = io.open(path, encoding="utf-8").read()
    count = text.count(old)
    if count < 1:
        print("REPLACE_COUNT=0")
        sys.exit(2)
    io.open(path, "w", encoding="utf-8", newline="").write(text.replace(old, new))
    print("REPLACE_COUNT=%d" % count)


main()
