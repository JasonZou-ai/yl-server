package cn.yl.modules.account.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 告知同意留痕（consent_record，ER-08）。个保法「知情同意」举证。
 *
 * <p>与业务授权表 {@code elder_authorization} 区分：本表记录对隐私政策的同意。{@code scope_json} 为授权收集范围快照， {@code
 * revoked_at} 记录撤回时间，构成同意生命周期留痕。
 */
@Data
@TableName("consent_record")
public class ConsentRecord {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String subjectType;
    private Long subjectId;
    private String policyCode;
    private String policyVersion;
    private LocalDateTime consentAt;
    private String consentChannel;
    private String scopeJson;
    private LocalDateTime revokedAt;
    private String ip;
    private Integer deleted;
    private LocalDateTime createdAt;
}
