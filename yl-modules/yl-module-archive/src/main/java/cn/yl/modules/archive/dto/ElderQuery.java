package cn.yl.modules.archive.dto;

import lombok.Data;

/**
 * 老人列表筛选入参（分页 + 多维筛选）。
 *
 * <p>可见范围由第三闸 {@code DataScopePermissionHandler} 行级收口，<b>权限码从宽、数据域从严</b>； 服务层不再手写机构过滤。
 */
@Data
public class ElderQuery {

    /** 页码（从 1 开始）。 */
    private Integer pageNum = 1;

    /** 每页条数。 */
    private Integer pageSize = 10;

    /**
     * 关键词：姓名（走 {@code name_hash} 精确匹配）或档案号 {@code elder_no} 前缀。
     *
     * <p><b>不可模糊查</b>：GCM 密文每次 IV 随机、不可比对，故姓名只能按 {@code name_hash} 精确匹配；档案号 {@code elder_no}
     * 为非隐私列，可按前缀匹配。未装配敏感编解码器（dev 无密钥）时， 因无法计算 {@code name_hash}，关键词仅按 {@code elder_no} 前缀匹配。
     */
    private String keyword;

    /** 在册状态筛选（0-迁出/死亡 1-在册 2-归档）。 */
    private Integer status;

    /** 归属机构筛选（第三闸已在更外层收口；此为应用层可选精筛）。 */
    private Long orgId;
}
