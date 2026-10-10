package cn.yl.modules.archive.dto;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 亲情绑定视图（D-4，出参）。
 *
 * <p><b>脱敏口径</b>：{@code elderName} 为 {@code SensitiveFieldSupport.maskName(decrypt(nameEnc))}（如
 * {@code 张*}）； <b>申请阶段（未生效，status=0/1）不下发</b>（{@code null}），防枚举。
 */
@Data
public class FamilyBindingView {

    /** 绑定关系 id（即 relationId）。 */
    private Long id;

    private Long elderId;

    /** 老人脱敏姓名；未生效阶段为 {@code null}。 */
    private String elderName;

    private String relation;

    /** 0-待确认 1-待机构审核 2-生效 3-已拒绝 4-已解绑/过期。 */
    private Integer status;

    private LocalDateTime authStartAt;

    private LocalDateTime authEndAt;

    private String rejectReason;
}
