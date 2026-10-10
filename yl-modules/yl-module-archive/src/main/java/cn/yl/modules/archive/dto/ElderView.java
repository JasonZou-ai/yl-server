package cn.yl.modules.archive.dto;

import lombok.Data;

/**
 * 老人档案视图（出参，<b>全部脱敏</b>）。
 *
 * <p>契约 {@code ElderView} 不含 {@code address}：住址仅在 {@code POST /api/v1/security/reveal} 中明文返回（{@code
 * x-sensitive-fields} 指"该资源含这些敏感字段"， 默认一律脱敏）。
 */
@Data
public class ElderView {

    private Long id;

    /** 对外档案编号（不含隐私）。 */
    private String elderNo;

    /** 姓名脱敏形态，如 {@code 张*}。 */
    private String name;

    /** 身份证掩码串，如 {@code 5301**********1234}。 */
    private String idCardMask;

    /** 手机号脱敏形态，如 {@code 138****5678}。 */
    private String phone;

    /** 0-女 1-男 2-未知。 */
    private Integer gender;

    private Integer age;

    private Long orgId;

    /** 0-迁出/死亡 1-在册 2-归档。 */
    private Integer status;
}
