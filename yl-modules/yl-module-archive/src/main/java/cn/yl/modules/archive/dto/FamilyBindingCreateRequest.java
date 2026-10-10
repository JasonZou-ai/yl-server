package cn.yl.modules.archive.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发起亲情绑定请求（D-4，{@code POST /api/v1/family/bindings}）。
 *
 * <p><b>身份一律取登录态</b>：请求体<b>不承载</b> {@code familyUserId}（由服务端从 {@code SecurityUtils} 取登录账号）， 这是
 * CR-M2-002 铁律。
 *
 * <p><b>核验字段</b>：{@code elderName} / {@code idCardNo} 为<b>服务端要求必填</b>（用于经 {@code name_hash} +
 * {@code id_card_hash} 精确核验被绑老人；明文<b>不落库</b>）。契约 {@code required} 数组暂未含二者（避免翻旗批次夹带语义变更，登记为
 * 待订正项）——服务端更严不违规（同 {@code ElderCreate.orgId} 先例）。
 */
@Data
public class FamilyBindingCreateRequest {

    /** 被绑定老人 id（来自端侧扫码原文）。 */
    @NotNull(message = "elderId 不能为空")
    private Long elderId;

    /** 关系：CHILD/SPOUSE/KIN/GUARDIAN。 */
    @NotBlank(message = "关系不能为空")
    private String relation;

    /** 核验方式：SCAN/PHONE/FACE（服务端白名单校验）。 */
    @NotBlank(message = "核验方式不能为空")
    @Pattern(regexp = "SCAN|PHONE|FACE", message = "核验方式取值须为 SCAN/PHONE/FACE")
    private String verifyMethod;

    /** 监护关系证明对象键（只存键，不落盘）。 */
    @Size(max = 255, message = "证明对象键长度不得超过 255")
    private String proofCosKey;

    /** 老人姓名（核验必填；不落明文）。 */
    @NotBlank(message = "老人姓名不能为空（核验必填）")
    @Size(max = 64, message = "老人姓名长度不得超过 64")
    private String elderName;

    /** 老人身份证号（核验必填；不落明文）。 */
    @NotBlank(message = "老人身份证号不能为空（核验必填）")
    @Size(max = 32, message = "身份证号长度不得超过 32")
    private String idCardNo;
}
