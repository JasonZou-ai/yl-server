package cn.yl.modules.archive.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import lombok.Data;

/**
 * 老人建档请求（{@code src} 明文提交，服务端加密落库）。
 *
 * <p>明文仅在传输层出现，落库一律经 {@code SensitiveFieldCodec}；{@code idCardNo} 禁止回显全文。
 *
 * <p><b>身份与机构口径（CR-M2-002 铁律）</b>：请求体<b>不承载</b>任何身份字段（如 {@code createdBy}）， 建档人一律取登录态；{@code orgId}
 * 可省略 —— {@code null} 时取登录态默认机构，非空时须落在 {@code LoginUser.orgIds()} 内，否则拒绝（20003）。
 */
@Data
public class ElderCreateRequest {

    /** 姓名（明文提交，服务端加密落库）。 */
    @NotBlank(message = "姓名不能为空")
    @Size(max = 64, message = "姓名长度不得超过 64")
    private String name;

    /** 身份证号（明文提交；禁止回显全文，落库为密文 + 摘要 + 脱敏串）。 */
    @Pattern(
            regexp =
                    "^[1-9]\\d{5}(18|19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]$",
            message = "身份证号格式不正确")
    private String idCardNo;

    /** 0-女 1-男 2-未知。 */
    @Min(value = 0, message = "性别取值须为 0/1/2")
    @Max(value = 2, message = "性别取值须为 0/1/2")
    private Integer gender;

    /** 出生日期。 */
    @Past(message = "出生日期须早于当前")
    private LocalDate birthDate;

    /** 手机号（明文提交）。 */
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    /** 住址（明文提交）。 */
    @Size(max = 256, message = "住址长度不得超过 256")
    private String address;

    /** 归属机构；可空 —— 缺省取登录态默认机构，显式传值须落在本人机构域内（20003）。 */
    private Long orgId;
}
