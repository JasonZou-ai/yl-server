package cn.yl.api.config;

import cn.yl.api.security.JwtAuthFilter;
import cn.yl.api.security.RestAccessDeniedHandler;
import cn.yl.api.security.RestAuthenticationEntryPoint;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Spring Security 6 配置（B1-2 / 子任务 rPX9p2）。
 *
 * <p>要点：
 *
 * <ul>
 *   <li>无状态（STATELESS）：不依赖 Session，令牌由四端自行携带
 *   <li>关闭 CSRF（纯 API + Bearer 令牌场景）
 *   <li>登录/刷新/验真/健康检查/接口文档放行，其余一律需认证
 *   <li>{@code @EnableMethodSecurity} 支持接口级 {@code @PreAuthorize} 细粒度鉴权（如「录入人 ≠ 复核人」）
 *   <li>未认证统一返回 401 + 统一响应体，不跳转登录页（CR-M2-007 <b>已实现</b>：见 {@code exceptionHandling}）
 *   <li>CORS 白名单由 {@code yl.cors.allowed-origins} 配置（Web 管理后台需要；双小程序/原生 App 不受浏览器同源限制）
 * </ul>
 *
 * <p><b>CR-M2-007（未认证统一口径）</b>：{@code filterChain} 显式装配 {@code exceptionHandling} —— 未携带/无效凭据落
 * {@link RestAuthenticationEntryPoint}（{@code 401 + R.fail(UNAUTHORIZED/10002)}）， 框架层拒绝（{@code
 * hasAuthority} 表达式等，本项目未使用）落 {@link RestAccessDeniedHandler} （{@code 403 +
 * R.fail(FORBIDDEN/10003)}）。响应体一律经全局同一 {@code ObjectMapper} 序列化为 {@code R}， 与 MVC
 * 口径一致。注意：业务层越权（{@code @RequiresPermission} 不满足）发生在 DispatcherServlet 内， 仍由 {@code
 * GlobalExceptionHandler} 收敛为 {@code 200 + R.code}，两条路径不重叠。
 */
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor(
        onConstructor_ =
                @SuppressFBWarnings(
                        value = "EI_EXPOSE_REP2",
                        justification =
                                "JwtAuthFilter 及入口点/拒绝处理器均为 Spring 容器管理的无状态单例 Bean，"
                                        + "构造器注入是标准 DI 用法"))
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    /** 未认证入口点（401 + R(10002)）。 */
    private final RestAuthenticationEntryPoint authenticationEntryPoint;

    /** 框架层拒绝处理器（403 + R(10003)）。 */
    private final RestAccessDeniedHandler accessDeniedHandler;

    @Value("${yl.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(
                                                "/api/v1/auth/login",
                                                "/api/v1/auth/login/**",
                                                "/api/v1/auth/login-by-ticket",
                                                "/api/v1/auth/refresh",
                                                "/api/v1/sms/**",
                                                "/api/v1/reports/verify",
                                                "/api/v1/system/health",
                                                "/api/v1/system/info",
                                                "/actuator/**",
                                                "/doc.html",
                                                "/webjars/**",
                                                "/v3/api-docs/**",
                                                "/swagger-ui/**",
                                                "/favicon.ico")
                                        .permitAll()
                                        .requestMatchers(HttpMethod.OPTIONS, "/**")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                // CR-M2-007：未认证 → 401 + R(10002)；框架层拒绝 → 403 + R(10003)
                .exceptionHandling(
                        ex ->
                                ex.authenticationEntryPoint(authenticationEntryPoint)
                                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** CORS 白名单。使用 {@code allowedOriginPatterns} 以支持带端口的本地调试地址； 生产环境通过环境变量收紧为管理后台正式域名。 */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins =
                Arrays.stream(allowedOrigins.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
        config.setAllowedOriginPatterns(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("X-Trace-Id"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    /** 口令散列：BCrypt（自适应强度），禁止明文/可逆存储。 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
