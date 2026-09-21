package cn.yl.modules.account.mapper;

import cn.yl.modules.account.domain.entity.LoginLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 登录日志 Mapper（login_log）。仅追加。 */
@Mapper
public interface LoginLogMapper extends BaseMapper<LoginLog> {}
