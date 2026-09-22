package cn.yl.modules.account.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启 Spring 定时任务调度（全仓首个定时任务：ER-14 留存到期清理）。
 *
 * <p><b>为什么是 {@code @Scheduled} 而不是 Quartz / XXL-Job</b>：
 *
 * <ul>
 *   <li>Quartz：需新增 11 张调度表与持久化配置，为「一天一次、幂等、无编排」的任务引入该重量级设施不划算；
 *   <li>XXL-Job：{@code xxl-job-core} 虽已在依赖中，但需要独立部署 admin 服务端与执行器注册，属运维侧投入， 且当前 {@code
 *       xxl.job.enabled=false}；M3 前引入会把「合规缺口」和「运维建设」绑在一条关键路径上；
 *   <li>{@code @Scheduled}：零新依赖、本迭代即可闭合缺口。
 * </ul>
 *
 * <p>迁移边界：业务动作都在 {@code ThirdPartyRetentionCleanupTask} 的普通方法里，{@code @Scheduled} 只是触发壳。将来迁 XXL-Job
 * 时，把触发壳换成 {@code @XxlJob("thirdPartyRetentionCleanup")} 即可，业务方法不动。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
