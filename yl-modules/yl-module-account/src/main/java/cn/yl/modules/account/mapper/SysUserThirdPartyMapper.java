package cn.yl.modules.account.mapper;

import cn.yl.modules.account.domain.entity.SysUserThirdParty;
import cn.yl.modules.account.domain.thirdparty.ExpiredBindingPurger;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 第三方账号绑定 Mapper（sys_user_third_party，ER-14）。
 *
 * <p>同时实现 {@link ExpiredBindingPurger}：留存到期清理走<b>手写 SQL 的物理删除</b>，不用 MyBatis-Plus 的 {@code
 * delete*}。 原因是全局逻辑删除配置（{@code logic-delete-field: deleted}）会把 {@code delete*} 改写成 {@code UPDATE
 * ... SET deleted=1}， 而合规要求的是<b>到期真删</b>。手写 SQL 不经过该改写，且条件只认 {@code retain_until}，与软删状态无关。
 */
@Mapper
public interface SysUserThirdPartyMapper
        extends BaseMapper<SysUserThirdParty>, ExpiredBindingPurger {

    /**
     * 物理删除已过保留期的绑定行（限批）。
     *
     * <p>{@code retain_until IS NOT NULL} 是硬条件：未进入注销保留期的行（NULL）永不清理，避免误删在用绑定。 走 {@code
     * idx_retain_until} 索引，{@code LIMIT} 控制单批规模。
     */
    @Override
    @Delete(
            "DELETE FROM sys_user_third_party WHERE retain_until IS NOT NULL AND retain_until < #{cutoff} LIMIT"
                    + " #{limit}")
    int purgeExpired(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    /** 统计已过保留期但尚未清理的行数。 */
    @Override
    @Select(
            "SELECT COUNT(*) FROM sys_user_third_party WHERE retain_until IS NOT NULL AND retain_until <"
                    + " #{cutoff}")
    long countExpired(@Param("cutoff") LocalDateTime cutoff);
}
