#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P2-8-A 断言缺口补强 —— 突变反向证明（QA 的 M3/M4/M13 在补强后应能变红）。

逐条：备份 → 改一处 → 跑定向测试 → 记录 → 逐字节还原 → md5 校验。
"""
import hashlib
import os
import subprocess
import sys

REPO = r"C:\Users\Administrator\WorkBuddy\2026-09-18-11-08-33\stage3_开发\yl-server"

ACCOUNT_TEST = (
    "mvn -o -pl yl-modules/yl-module-account -am test -Dtest=SmsCodeServiceTest "
    "-Dsurefire.failIfNoSpecifiedTests=false "
    "-Dspotless.check.skip=true -Dcheckstyle.skip=true -Dspotbugs.skip=true"
)
INFRA_TEST = (
    "mvn -o -pl yl-infrastructure -am test -Dtest=LoggingSmsSenderTest,UnavailableSmsSenderTest "
    "-Dsurefire.failIfNoSpecifiedTests=false "
    "-Dspotless.check.skip=true -Dcheckstyle.skip=true -Dspotbugs.skip=true"
)
COMMON_TEST = (
    "mvn -o -pl yl-common test -Dtest=SensitiveFieldCodecTest "
    "-Dsurefire.failIfNoSpecifiedTests=false "
    "-Dspotless.check.skip=true -Dcheckstyle.skip=true -Dspotbugs.skip=true"
)
BOOTSTRAP_TEST = (
    "mvn -o -pl yl-bootstrap -am test -Dtest=DevCredentialStartupGuardTest "
    "-Dsurefire.failIfNoSpecifiedTests=false "
    "-Dspotless.check.skip=true -Dcheckstyle.skip=true -Dspotbugs.skip=true"
)

SMS_SERVICE = os.path.join(
    REPO,
    r"yl-modules\yl-module-account\src\main\java\cn\yl\modules\account\service\sms\SmsCodeService.java",
)
LOGGING_SENDER = os.path.join(
    REPO,
    r"yl-infrastructure\src\main\java\cn\yl\infrastructure\sms\LoggingSmsSender.java",
)
SENSITIVE_CODEC = os.path.join(
    REPO,
    r"yl-common\src\main\java\cn\yl\common\security\SensitiveFieldCodec.java",
)
DEV_GUARD = os.path.join(
    REPO,
    r"yl-bootstrap\src\main\java\cn\yl\bootstrap\security\DevCredentialStartupGuard.java",
)

MUTATIONS = [
    {
        "id": "M4'",
        "desc": "hashPhone 退化为明文手机号（QA M4 缺口）",
        "file": SMS_SERVICE,
        "old": "        return codec.hashForSearch(phone);",
        "new": "        return phone;",
        "cmd": ACCOUNT_TEST,
    },
    {
        "id": "M3'",
        "desc": "摘掉 phone:60s 频控维度（QA M3 缺口）",
        "file": SMS_SERVICE,
        "old": '        checkLimit("sms:rl:ph:" + phoneHash + ":60s", WINDOW_60S, limits.getPhone60s());\n',
        "new": "        // MUTATED: 60s 维度已摘除\n",
        "cmd": ACCOUNT_TEST,
    },
    {
        "id": "M13'",
        "desc": "dev-log-code=false 时仍打印验证码（QA M13 缺口）",
        "file": LOGGING_SENDER,
        "old": '            log.info("短信验证码下发（日志桩，验证码已隐藏）：phone={} purpose={}", maskedPhone, purpose);',
        "new": (
            '            log.info('
            '"短信验证码下发（日志桩，验证码已隐藏）：phone={} purpose={} code={}", '
            "maskedPhone, purpose, code);"
        ),
        "cmd": INFRA_TEST,
    },
    {
        "id": "M14'",
        "desc": "摘掉脱敏守卫：日志改打印明文手机号（Step 3 立规的红线类行为）",
        "file": LOGGING_SENDER,
        "old": '        String maskedPhone = MaskUtils.maskPhone(phone);',
        "new": "        String maskedPhone = phone; // MUTATED: 脱敏守卫已摘除",
        "cmd": INFRA_TEST,
    },
    # ===== P2-8-R12：联调凭据启动守卫（用户 2026-10-10 要求"摘掉守卫必须变红"） =====
    {
        "id": "G1",
        "desc": "摘掉「已知泄露散列」金丝雀（公开口令可再次登录任何环境）",
        "file": DEV_GUARD,
        "old": "            if (hits > 0) {",
        "new": "            if (false) { // MUTATED: 泄露散列金丝雀已摘除",
        "cmd": BOOTSTRAP_TEST,
    },
    {
        "id": "G2",
        "desc": "摘掉「非 dev profile 禁止联调账号」分支",
        "file": DEV_GUARD,
        "old": "        if (!devProfile && !devUsernames.isEmpty()) {",
        "new": "        if (false) { // MUTATED: 非 dev 账号禁令已摘除",
        "cmd": BOOTSTRAP_TEST,
    },
    {
        "id": "G3",
        "desc": "篡改金丝雀常量（守卫「看着在」，实际不再盯真泄露值）",
        "file": DEV_GUARD,
        "old": '"$2a$10$JudcPt.pgXFygWWNKYQs6OaRLs0XufV7hLmphlz4w9ggRDbtZP.Hq"',
        "new": '"$2a$10$MUTATEDCANARYVALUE000000000000000000000000000000000000"',
        "cmd": BOOTSTRAP_TEST,
    },
    # ===== P2-8-R10：SensitiveFieldCodec（R1 加密 / R2 摘要） =====
    {
        "id": "G4",
        "desc": "R1 解密失败改为**静默返回密文**（去掉 fail-closed，篡改/换密钥不再报错）",
        "file": SENSITIVE_CODEC,
        "old": '            throw new IllegalStateException("敏感字段解密失败", e);',
        "new": "            return cipherText; // MUTATED: 解密失败已静默降级",
        "cmd": COMMON_TEST,
    },
    {
        "id": "G5",
        "desc": "R2 摘要退化为**无密钥** SHA-256（丢 HMAC，摘要可被反推）",
        "file": SENSITIVE_CODEC,
        "old": (
            "            Mac mac = Mac.getInstance(HMAC_SHA256);\n"
            "            mac.init(hmacKey);\n"
            "            byte[] digest = mac.doFinal(plain.getBytes(StandardCharsets.UTF_8));"
        ),
        "new": (
            "            byte[] digest =\n"
            "                    java.security.MessageDigest.getInstance(\"SHA-256\")\n"
            "                            .digest(plain.getBytes(StandardCharsets.UTF_8));"
        ),
        "cmd": COMMON_TEST,
    },
    {
        "id": "G6",
        "desc": "R1 加密退化为恒等返回（明文落库）",
        "file": SENSITIVE_CODEC,
        "old": "            return Base64.getEncoder().encodeToString(out);",
        "new": "            return plain; // MUTATED: 加密已退化为明文透传",
        "cmd": COMMON_TEST,
    },
]


def md5(path):
    with open(path, "rb") as fh:
        return hashlib.md5(fh.read()).hexdigest()


def main():
    results = []
    for mut in MUTATIONS:
        path = mut["file"]
        original = open(path, "rb").read()
        before = md5(path)
        text = original.decode("utf-8")
        if mut["old"] not in text:
            results.append((mut["id"], "SKIP", "锚点未命中（原文已变）", before, before))
            continue
        mutated = text.replace(mut["old"], mut["new"], 1)
        with open(path, "wb") as fh:
            fh.write(mutated.encode("utf-8"))
        try:
            proc = subprocess.run(mut["cmd"], cwd=REPO, shell=True, capture_output=True)
            # ★ mvn 在 Windows 上输出 GBK，严禁按 UTF-8 解码（会抛 UnicodeDecodeError 并让 red 判定失效）
            out = (proc.stdout or b"").decode("utf-8", errors="replace") + (
                proc.stderr or b""
            ).decode("utf-8", errors="replace")
            # 已跳过 spotless/checkstyle/spotbugs ⇒ 非 0 退出码只可能来自测试失败
            red = proc.returncode != 0
            if red:
                failed = [
                    ln.strip()
                    for ln in out.splitlines()
                    if "FAILURE!" in ln or "Failures: " in ln and "Failures: 0" not in ln
                ]
                detail = " ; ".join(failed[:4]) or "非 0 退出码"
                results.append((mut["id"], "RED", detail[:300], before, before))
            else:
                results.append((mut["id"], "GREEN", "突变未被检出（断言缺口仍在）", before, before))
        finally:
            with open(path, "wb") as fh:
                fh.write(original)
        after = md5(path)
        results[-1] = results[-1][:4] + (after,)

    print("\n================= 突变反向证明结果 =================")
    for mid, verdict, detail, before, after in results:
        restored = "OK" if before == after else "!! 还原失败 !!"
        print(f"[{mid}] {verdict} | 还原={restored} | {detail}")
    bad = [r for r in results if r[1] != "RED"]
    print("\n结论：", "全部变红（新断言有区分力）" if not bad else f"仍有缺口：{[r[0] for r in bad]}")
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
