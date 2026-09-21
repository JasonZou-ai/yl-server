package cn.yl.modules.account.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 登录日志（login_log）。四端登录统一留痕，成功与失败均记录（失败原因脱敏为枚举文案）。
 *
 * <p>无 {@code deleted} 列：登录日志仅追加。
 */
@Data
@TableName("login_log")
public class LoginLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;
    private String username;
    private String loginType;
    private String deviceType;
    private String ip;
    private String ua;
    private Integer success;
    private String failReason;
    private LocalDateTime createdAt;
}
