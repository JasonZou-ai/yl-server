package cn.yl.modules.account.config;

import cn.yl.modules.account.security.DataScopeReadOnlyGuardInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 账号模块 Web MVC 装配（B2 / rwkav6）。
 *
 * <p>注册只读全局守卫：作用于全部 {@code /api/**}，排除认证端点（{@code /api/v1/auth/**}，含二次验证凭据获取）与
 * 系统端点，避免监管账号在获取凭据阶段被自身只读规则阻断。
 */
@Configuration
public class AccountWebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new DataScopeReadOnlyGuardInterceptor())
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/v1/auth/**", "/api/v1/system/**", "/actuator/**");
    }
}
