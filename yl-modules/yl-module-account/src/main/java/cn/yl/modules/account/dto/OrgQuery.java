package cn.yl.modules.account.dto;

import lombok.Data;

/** R6 机构列表查询入参（{@code GET /api/v1/account/orgs}）。 */
@Data
public class OrgQuery {

    private Integer pageNum;
    private Integer pageSize;

    /** 关键字：按机构名/机构编码前缀匹配（可选）。 */
    private String keyword;
}
