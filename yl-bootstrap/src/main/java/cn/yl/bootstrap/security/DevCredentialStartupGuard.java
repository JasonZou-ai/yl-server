package cn.yl.bootstrap.security;

import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 联调凭据启动守卫（P2-8-R12，闭环 P2-8-R9）。
 *
 * <h3>它防的是什么</h3>
 *
 * <p>2026-10-10 查实：dev 种子账号的明文口令与 BCrypt 散列已随公开 GitHub 历史**永久暴露**（无法回收）。Dockerfile 用 {@code prod}
 * profile 启动，而 {@code docker-compose.yml} 把整个 {@code docker/mysql/init/} 挂成 {@code
 * docker-entrypoint-initdb.d} —— 任何**未来**一次部署都可能静默地把「两个能用公开口令登录的真实账号」带上去，且当时不会有人记得残留条目。 「当前无生产部署 ⇒
 * 暴露面 = 0」这句话是有保质期的。
 *
 * <p><b>轮换只让当前口令失效；本守卫让这类问题结构性地不可能发生。</b>
 *
 * <h3>两道检查（都 fail-closed：命中即抛异常 ⇒ 启动失败）</h3>
 *
 * <ol>
 *   <li><b>已知泄露散列金丝雀（全 profile，含 dev）</b> —— 库中任一账号的 {@code password_hash} 等于 {@link
 *       #KNOWN_LEAKED_PASSWORD_HASHES} 中的值即拒绝启动。刻意不限 profile：只要那个公开口令还能登进任何环境，就必须炸。
 *   <li><b>非 dev profile 禁止 {@code *_dev} 账号</b> —— 非 dev 环境下库里仍存在 {@code assessor_dev} 等联调账号即拒绝启动。
 *       （dev 环境允许存在，但会打 WARN 提醒当前口令只在本地有效。）
 * </ol>
 *
 * <h3>为什么不加开关属性</h3>
 *
 * <p>没有 {@code yl.security.*.enabled} 之类的旁路开关：能用配置关掉的守卫等于没有守卫。真要有 ex. demo 环境需要联调账号，就把它标成 {@code
 * dev} profile。
 */
@Slf4j
@Component
public class DevCredentialStartupGuard implements ApplicationRunner {

    /**
     * 已知泄露口令的 BCrypt 散列（P2-8-R9/R12）。
     *
     * <p>四个 dev 账号原先共用此散列，明文口令写在种子注释与 7 篇文档里，均已推送至公开仓库 ⇒ 永久暴露。轮换后库中不应再出现该值。
     *
     * <p>后续若再发生相似泄露，<b>在此处追加</b>即可（每个泄露值一行），无需改动判定逻辑。
     */
    static final List<String> KNOWN_LEAKED_PASSWORD_HASHES =
            List.of("$2a$10$JudcPt.pgXFygWWNKYQs6OaRLs0XufV7hLmphlz4w9ggRDbtZP.Hq");

    /** 允许携带联调账号的 profile。 */
    static final String DEV_PROFILE = "dev";

    private final DevCredentialProbe probe;

    private final Environment environment;

    public DevCredentialStartupGuard(DevCredentialProbe probe, Environment environment) {
        this.probe = probe;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> activeProfiles = Arrays.asList(environment.getActiveProfiles());
        boolean devProfile = activeProfiles.contains(DEV_PROFILE);

        // ① 已知泄露散列金丝雀：全 profile 生效（含 dev）—— 只要公开口令还能登录，就必须炸。
        for (String leaked : KNOWN_LEAKED_PASSWORD_HASHES) {
            long hits = probe.countByPasswordHash(leaked);
            if (hits > 0) {
                throw new IllegalStateException(
                        "启动中止：检测到 "
                                + hits
                                + " 个账号仍在使用「已知泄露」的密码散列（P2-8-R9/R12）。"
                                + " 该散列对应的明文口令已随公开 Git 历史永久暴露，必须立即轮换："
                                + " 更新 docker/mysql/init/04_seed_dev_accounts.sql 并重跑数据库初始化"
                                + "（或对存量库执行 UPDATE sys_user SET password_hash=... 后重启）。"
                                + " 当前 profile="
                                + activeProfiles);
            }
        }

        // ② 非 dev profile 禁止联调账号存在。
        List<String> devUsernames = probe.listDevUsernames();
        if (!devProfile && !devUsernames.isEmpty()) {
            throw new IllegalStateException(
                    "启动中止：非 "
                            + DEV_PROFILE
                            + " profile 下检测到联调账号 "
                            + devUsernames
                            + "（P2-8-R12）。这些账号的种子口令曾公开泄露；"
                            + " 对外环境必须删除它们（或改用独立迁移通道初始化数据库，不要挂载 docker/mysql/init/）。"
                            + " 当前 profile="
                            + activeProfiles);
        }
        if (devProfile && !devUsernames.isEmpty()) {
            log.warn(
                    "当前为 {} profile，库中存在联调账号 {} —— 其口令仅对本机有效，禁止用于任何对外环境",
                    DEV_PROFILE,
                    devUsernames);
        }
        log.info("联调凭据启动守卫通过：无已知泄露散列，profile={}，联调账号 {} 个", activeProfiles, devUsernames.size());
    }
}
