package cn.yl.api.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
// jjwt 0.12.x 的 issuedAt/expiration 仅接受 java.util.Date，无 Instant 重载。
// 时间计算仍以 java.time 为准，仅在本适配类内豁免「禁止 java.util.Date」规则。
// CHECKSTYLE_OFF: IllegalImport
import java.util.Date;
// CHECKSTYLE_ON: IllegalImport
import java.util.List;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * JWT 令牌签发与解析（B1-2 / 子任务 rPX9p2）。
 *
 * <p>双令牌：access（默认 2h，短时）+ refresh（默认 14d，长时）。四端登录方式不同（微信/抖音授权、 Apple ID / Face
 * ID、账号密码），但登录成功后统一换发本服务的 JWT，实现 PRD §8「API 四端完全一致，由后端统一供给」。
 *
 * <p>密钥来自 {@code yl.security.jwt.secret}（生产由环境变量注入）。
 *
 * <p>声明为 {@code final}：构造器会在密钥强度不足时抛异常，final 类可消除对象「半初始化」被 finalizer 攻击利用的可能（SpotBugs
 * CT_CONSTRUCTOR_THROW）。
 */
@Component
public final class JwtTokenProvider {

    public static final String CLAIM_USER_ID = "uid";
    public static final String CLAIM_ROLES = "roles";
    public static final String CLAIM_ORG_ID = "orgId";
    public static final String CLAIM_TYPE = "typ";

    /** 权限码集合（access 令牌专有，CR-M2-002 载荷补齐）。 */
    public static final String CLAIM_PERMS = "perms";

    /** 归属机构 ID 集合（access 令牌专有，第三闸数据域用）。 */
    public static final String CLAIM_ORG_IDS = "orgIds";

    /** 数据域口径 1/2/3/4（access 令牌专有，第三闸数据域用）。 */
    public static final String CLAIM_DATA_SCOPE = "ds";

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final Duration accessTtl;
    private final Duration refreshTtl;
    private final String issuer;

    public JwtTokenProvider(
            @Value("${yl.security.jwt.secret}") String secret,
            @Value("${yl.security.jwt.access-token-ttl:2h}") Duration accessTtl,
            @Value("${yl.security.jwt.refresh-token-ttl:14d}") Duration refreshTtl,
            @Value("${yl.security.jwt.issuer:yl-server}") String issuer) {
        // HS256 要求密钥 ≥256 位（32 字节），启动即校验，避免线上签名强度不足
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
        this.issuer = issuer;
    }

    /**
     * 签发 access 令牌（含权限/机构域/数据域三项载荷）。
     *
     * <p><b>为什么必须携带这三项（CR-M2-002 缺口 B）</b>：第二闸权限码校验读 {@code LoginUser.perms}、第三闸数据域读 {@code
     * LoginUser.dataScope/orgIds}。若令牌不携带，过滤器只能用「最严格默认值」装配登录态（perms 空、dataScope=1），于是：
     *
     * <ul>
     *   <li>一旦端点标注权限码 → 所有账号**全量 403**（假性收紧，非真收紧）；
     *   <li>数据域被压成「仅本人」→ 评估员/机构管理员**看不到本机构评估单**（功能不可用）。
     * </ul>
     *
     * <p><b>口径权衡</b>：本实现把权限码直接签进令牌（令牌约增 0.5–0.8KB），换取过滤器零额外查询、无新增失败面；代价是权限变更在 access 存活期（默认
     * 2h）内不即时生效，刷新令牌时会按最新档案重新签发。若后续权限点规模显著增长或要求即时回收，再切换为「签 {@code permVer} 版本号 + 服务端查表缓存」方案（登记见
     * CR-M2-002 §八）。
     *
     * @param userId 用户 ID
     * @param roles 角色码集合
     * @param orgId 默认机构 ID（可空）
     * @param perms 权限码集合（第二闸）
     * @param orgIds 归属机构 ID 集合（第三闸 scope=2 用）
     * @param dataScope 数据域口径（第三闸：1 本人 / 2 本机构 / 3 全量 / 4 只读全局）
     */
    public String createAccessToken(
            long userId,
            List<String> roles,
            Long orgId,
            List<String> perms,
            List<Long> orgIds,
            int dataScope) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(issuer)
                .subject(String.valueOf(userId))
                .claim(CLAIM_USER_ID, userId)
                .claim(CLAIM_ROLES, roles == null ? List.of() : roles)
                .claim(CLAIM_ORG_ID, orgId)
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .claim(CLAIM_PERMS, perms == null ? List.of() : perms)
                .claim(CLAIM_ORG_IDS, orgIds == null ? List.of() : orgIds)
                .claim(CLAIM_DATA_SCOPE, dataScope)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .signWith(key)
                .compact();
    }

    /**
     * 签发 refresh 令牌（仅身份 4 项声明）。
     *
     * <p>refresh 令牌的唯一用途是换发新 access 令牌，而换发时会按最新档案重新装载权限/机构域（见 {@code AuthService.refresh}），故
     * refresh 令牌**不携带**权限类载荷：既缩小暴露面，也保证权限变更可在刷新时生效。
     */
    public String createRefreshToken(long userId, List<String> roles, Long orgId) {
        return build(userId, roles, orgId, TYPE_REFRESH, refreshTtl);
    }

    private String build(long userId, List<String> roles, Long orgId, String type, Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(issuer)
                .subject(String.valueOf(userId))
                .claim(CLAIM_USER_ID, userId)
                .claim(CLAIM_ROLES, roles)
                .claim(CLAIM_ORG_ID, orgId)
                .claim(CLAIM_TYPE, type)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** 解析并验签；非法或过期抛 {@code JwtException}，由调用方转为 401。 */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    /**
     * 计算令牌的<b>剩余有效期</b>（D-4 `authLogout` 用于黑名单 TTL）。
     *
     * <p>取 {@code exp - now}：正值即剩余秒数；已过期返回 {@link Duration#ZERO}（调用方据此跳过登记）。{@code exp} 缺失或令牌不可解析
     * 时退化为 access TTL（宁可多留一小段黑名单，也不漏登记）。
     *
     * @param token 令牌
     * @return 剩余时长（非负；不可解析时退化为 access TTL）
     */
    public Duration remainingTtl(String token) {
        try {
            Claims claims = parse(token);
            Date expiration = claims.getExpiration();
            if (expiration == null) {
                return accessTtl;
            }
            Duration remaining = Duration.between(Instant.now(), expiration.toInstant());
            return remaining.isNegative() ? Duration.ZERO : remaining;
        } catch (JwtException | IllegalArgumentException e) {
            return accessTtl;
        }
    }

    public Duration getAccessTtl() {
        return accessTtl;
    }

    public Duration getRefreshTtl() {
        return refreshTtl;
    }
}
