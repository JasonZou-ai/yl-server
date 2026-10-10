package cn.yl.bootstrap.security;

import java.util.List;

/**
 * 启动守卫的<b>只读探针</b>（P2-8-R12）。
 *
 * <p>存在的理由：把「怎么读」与「怎么判」分开。守卫的判定逻辑是本条的合规红线， 必须有「摘掉守卫即变红」的行为断言（见 {@code
 * docs/quality/red-line-guard-mutation-proof.md}）； 而真接了数据库就没法用纯单测驱动。 故判定所需的全部输入经此接口注入，生产实现走
 * MyBatis，测试实现是内存桩。
 */
public interface DevCredentialProbe {

    /**
     * 未删除的 {@code *_dev} 联调账号登录名（非空即代表库里存在联调账号）。
     *
     * @return 登录名列表，按登录名升序；无则空列表（不得返回 null）
     */
    List<String> listDevUsernames();

    /**
     * 密码散列等于给定值的未删除账号数。
     *
     * @param passwordHash BCrypt 散列字面值
     * @return 命中行数
     */
    long countByPasswordHash(String passwordHash);
}
