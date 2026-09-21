package cn.yl.modules.account.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 机构成员（sys_org_member）。用户多机构多角色归属；同一用户在同一机构的不同角色为多行。
 *
 * <p>is_default=1 表示登录默认机构；status 控制成员生效状态。
 */
@Data
@TableName("sys_org_member")
public class SysOrgMember {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long orgId;
    private Long userId;
    private Long roleId;
    private Integer isDefault;
    private Integer status;
    private Integer deleted;
    private LocalDateTime createdAt;
}
