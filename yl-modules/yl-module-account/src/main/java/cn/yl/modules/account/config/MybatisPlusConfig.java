package cn.yl.modules.account.config;

import cn.yl.modules.account.security.DataScopePermissionHandler;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件装配（B2 / rwkav6）。
 *
 * <p>装载两个内部拦截器：数据域过滤（第三闸，{@link DataPermissionInterceptor} + {@link DataScopePermissionHandler}）
 * 与分页。分页置于末位，符合 MyBatis-Plus 推荐顺序（数据域过滤需先于分页改 SQL）。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(
                new DataPermissionInterceptor(new DataScopePermissionHandler()));
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
