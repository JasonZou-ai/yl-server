package cn.yl.modules.archive.mapper;

import cn.yl.modules.archive.domain.entity.Elder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 老人档案 Mapper（elder）。
 *
 * <p>所有查询经 MyBatis-Plus 生成 SQL，因而自动受第三闸 {@code DataScopePermissionHandler} 的行级收口（scope=1 → {@code
 * elder.user_id = 我}）。
 */
@Mapper
public interface ElderMapper extends BaseMapper<Elder> {}
