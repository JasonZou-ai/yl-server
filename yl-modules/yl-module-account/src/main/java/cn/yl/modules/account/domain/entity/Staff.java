package cn.yl.modules.account.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 机构人员（staff）。评估员/照护员/机构管理员资质档案，cert_expire_at 用于到期前 30 天预警（rOhkSI）。
 *
 * <p>姓名密文落库（staff_name_enc），cert_no 为资质证书编号（非敏感明文，按业务需要保留）。
 */
@Data
@TableName("staff")
public class Staff {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long orgId;
    private Long userId;
    private String staffNameEnc;
    private Integer staffType;
    private String certNo;
    private LocalDate certExpireAt;
    private String title;
    private Integer status;
    private Integer deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
