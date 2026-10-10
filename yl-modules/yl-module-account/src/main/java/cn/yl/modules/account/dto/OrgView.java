package cn.yl.modules.account.dto;

import lombok.Data;

/**
 * R6 机构视图（列表/详情）。
 *
 * <p><b>不含</b> {@code contactPhoneEnc}（机构联系方式密文列）—— 契约只声明机构公开信息字段，故不返回密文，避免泄露可解密线索。
 */
@Data
public class OrgView {

    private Long id;
    private String orgCode;
    private String orgName;
    private Integer orgType;
    private String regionCode;
    private String address;
    private String contactName;
    private Integer status;
}
