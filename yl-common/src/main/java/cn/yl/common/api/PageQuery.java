package cn.yl.common.api;

import lombok.Data;

/**
 * 统一分页请求（四端一致）。
 *
 * <p>约定 {@code pageNum} 从 1 起，{@code pageSize} 上限 100，防止一次拉取过大导致慢查询（PRD §1「筛选响应 &lt;1s」）。
 */
@Data
public class PageQuery {

    /** 每页上限，超过则截断，防止大页拖垮数据库 */
    public static final int MAX_PAGE_SIZE = 100;

    private int pageNum = 1;

    private int pageSize = 20;

    /** 排序字段（服务端需按白名单校验，禁止直接拼接列名，防注入） */
    private String sortBy;

    /** 排序方向：asc / desc */
    private String sortOrder = "desc";

    public int normalizePageNum() {
        return pageNum < 1 ? 1 : pageNum;
    }

    public int normalizePageSize() {
        if (pageSize < 1) {
            return 20;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}
