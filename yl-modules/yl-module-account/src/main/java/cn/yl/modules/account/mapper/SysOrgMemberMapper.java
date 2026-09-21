package cn.yl.modules.account.mapper;

import cn.yl.modules.account.domain.entity.SysOrgMember;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 机构成员 Mapper（sys_org_member）。 */
@Mapper
public interface SysOrgMemberMapper extends BaseMapper<SysOrgMember> {

    /** 查询用户在所有机构的成员关系（未删除）。 */
    @Select("SELECT * FROM sys_org_member WHERE user_id = #{userId} AND deleted = 0")
    List<SysOrgMember> selectByUserId(@Param("userId") Long userId);
}
