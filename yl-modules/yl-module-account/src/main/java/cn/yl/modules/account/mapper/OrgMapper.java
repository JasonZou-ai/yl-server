package cn.yl.modules.account.mapper;

import cn.yl.modules.account.domain.entity.Org;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 机构 Mapper（org）。D-3 R6 组织管理端点的持久化入口。 */
@Mapper
public interface OrgMapper extends BaseMapper<Org> {}
