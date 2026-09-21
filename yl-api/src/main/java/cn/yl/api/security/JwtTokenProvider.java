package cn.yl.api.security;

import io.jsonwebtoken.Claims;
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

    public String createAccessToken(long userId, List<String> roles, Long orgId) {
        return build(userId, roles, orgId, TYPE_ACCESS, accessTtl);
    }

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

    public Duration getAccessTtl() {
        return accessTtl;
    }

    public Duration getRefreshTtl() {
        return refreshTtl;
    }
}
