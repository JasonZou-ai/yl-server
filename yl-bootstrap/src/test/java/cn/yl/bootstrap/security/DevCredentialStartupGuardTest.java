package cn.yl.bootstrap.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

/**
 * {@link DevCredentialStartupGuard} 的行为门禁（P2-8-R12）。
 *
 * <h3>为什么必须有它</h3>
 *
 * <p>这条守卫是「防止未来某次部署静默带着公开口令上线」的**唯一**结构性机制。按 {@code docs/quality/red-line-guard-mutation-proof.md}
 * §2.2，承载合规红线的行为必须有「摘掉守卫即变红」的断言； 本类即该断言，并已用突变反向证明有区分力（见 {@code scripts/dev/p28-mutation-recheck.py}
 * 的 G1/G2/G3）。
 *
 * <p>刻意<b>不</b>真连数据库：判定所需输入全部经 {@link DevCredentialProbe} 注入， 这样断言观测的是「守卫的判定行为」而不是「JDBC 通不通」。
 */
class DevCredentialStartupGuardTest {

    private static final String LEAKED_HASH =
            "$2a$10$JudcPt.pgXFygWWNKYQs6OaRLs0XufV7hLmphlz4w9ggRDbtZP.Hq";

    /** 内存桩：记录被问过什么，并按需返回命中数。 */
    private static final class StubProbe implements DevCredentialProbe {
        private final List<String> devUsernames;
        private final long leakedHits;
        private final List<String> queriedHashes = new ArrayList<>();

        StubProbe(List<String> devUsernames, long leakedHits) {
            this.devUsernames = devUsernames;
            this.leakedHits = leakedHits;
        }

        @Override
        public List<String> listDevUsernames() {
            return devUsernames;
        }

        @Override
        public long countByPasswordHash(String passwordHash) {
            queriedHashes.add(passwordHash);
            return leakedHits;
        }
    }

    private static DevCredentialStartupGuard guard(DevCredentialProbe probe, String... profiles) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles(profiles);
        return new DevCredentialStartupGuard(probe, environment);
    }

    @Test
    @DisplayName("已知泄露散列仍在库中 → 拒绝启动（即便 profile=dev）")
    void failsWhenKnownLeakedHashStillPresent() {
        StubProbe probe = new StubProbe(List.of("assessor_dev"), 1L);

        assertThatThrownBy(() -> guard(probe, "dev").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已知泄露")
                .hasMessageContaining("1 个账号");
    }

    @Test
    @DisplayName("金丝雀必须查的是真·泄露散列本身（钉死期望值，防常量被改坏）")
    void canaryQueriesTheActualLeakedHash() {
        StubProbe probe = new StubProbe(List.of(), 0L);

        guard(probe, "dev").run(null);

        assertThat(probe.queriedHashes).containsExactly(LEAKED_HASH);
        assertThat(DevCredentialStartupGuard.KNOWN_LEAKED_PASSWORD_HASHES).contains(LEAKED_HASH);
    }

    @Test
    @DisplayName("非 dev profile 下存在联调账号 → 拒绝启动并点名账号")
    void failsWhenDevAccountsExistOutsideDevProfile() {
        StubProbe probe = new StubProbe(List.of("assessor_dev", "orgadmin_dev"), 0L);

        assertThatThrownBy(() -> guard(probe, "prod").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("assessor_dev")
                .hasMessageContaining("orgadmin_dev")
                .hasMessageContaining("prod");
    }

    @Test
    @DisplayName("dev profile 下存在联调账号 → 放行（库已轮换，金丝雀不命中）")
    void allowsDevAccountsInDevProfile() {
        StubProbe probe =
                new StubProbe(
                        List.of("assessor_dev", "orgadmin_dev", "elder_dev", "family_dev"), 0L);

        assertThatCode(() -> guard(probe, "dev").run(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("干净库 + 任意 profile → 放行")
    void allowsCleanDatabase() {
        assertThatCode(() -> guard(new StubProbe(List.of(), 0L), "prod").run(null))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard(new StubProbe(List.of(), 0L), "dev").run(null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★ 回归：曾出现过的真实失败形态 —— 残留 elder 绑定不属本守卫职责，守卫只看账号")
    void guardScopeIsAccountsOnly() {
        // 本用例锁定守卫的职责边界：它不检查业务表残留（那是 SMOKE-R1 的领域）。
        // 若有人把守卫扩成"顺带查业务表"，这里不会红；但若有人把账号检查删掉，上面三条会红。
        StubProbe probe = new StubProbe(List.of(), 0L);

        assertThatCode(() -> guard(probe, "prod").run(null)).doesNotThrowAnyException();
        assertThat(probe.queriedHashes).hasSize(1);
    }
}
