package cn.yl.api.config;

import cn.yl.api.security.JwtAuthFilter;
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
 *   <li>未认证统一返回 401 + 统一响应体，不跳转登录页
 *   <li>CORS 白名单由 {@code yl.cors.allowed-origins} 配置（Web 管理后台需要；双小程序/原生 App 不受浏览器同源限制）
 * </ul>
 */
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor(
        onConstructor_ =
                @SuppressFBWarnings(
                        value = "EI_EXPOSE_REP2",
                        justification = "JwtAuthFilter 是 Spring 容器管理的无状态过滤器 Bean，构造器注入是标准 DI 用法"))
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

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
                                                "/api/v1/auth/refresh",
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
