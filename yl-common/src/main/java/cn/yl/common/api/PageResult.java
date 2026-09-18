package cn.yl.common.api;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import lombok.Getter;

/**
 * 统一分页响应。
 *
 * <p>不可变对象：内部列表在构造时做快照，getter 仅返回不可变视图，避免调用方通过返回值改写内部分页数据。
 *
 * @param <T> 列表元素类型
 */
@Getter
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private final List<T> list;
    private final long total;
    private final int pageNum;
    private final int pageSize;

    private PageResult(List<T> list, long total, int pageNum, int pageSize) {
        this.list = list == null ? Collections.emptyList() : List.copyOf(list);
        this.total = total;
        this.pageNum = pageNum;
        this.pageSize = pageSize;
    }

    /**
     * 分页数据（不可变视图）。
     *
     * <p>显式声明以覆盖 Lombok 生成版本；返回值不可修改，调用方无法借此污染分页结果。
     */
    public List<T> getList() {
        return Collections.unmodifiableList(list);
    }

    public static <T> PageResult<T> of(List<T> list, long total, int pageNum, int pageSize) {
        return new PageResult<>(list, total, pageNum, pageSize);
    }

    public static <T> PageResult<T> empty(int pageNum, int pageSize) {
        return new PageResult<>(Collections.emptyList(), 0L, pageNum, pageSize);
    }

    /** 总页数，供前端分页控件直接使用 */
    public int getPages() {
        if (pageSize <= 0) {
            return 0;
        }
        return (int) ((total + pageSize - 1) / pageSize);
    }
}
