package cn.yl.modules.account.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 机构（org）。机构公开信息（机构名/地址/联系人）不属于个人敏感信息，不在脱敏强制范围。 */
@Data
@TableName("org")
public class Org {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String orgCode;
    private String orgName;
    private Integer orgType;
    private String regionCode;
    private String address;
    private String contactName;
    private String contactPhoneEnc;
    private Integer status;
    private Integer deleted;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
