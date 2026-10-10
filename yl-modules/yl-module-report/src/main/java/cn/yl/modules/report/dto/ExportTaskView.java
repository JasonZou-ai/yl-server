package cn.yl.modules.report.dto;

import lombok.Data;

/** B9 导出任务视图。 */
@Data
public class ExportTaskView {

    private Long id;
    private String taskNo;
    private String exportType;
    private String format;
    private Integer rowCount;
    private Integer status;
    private String fileCosKey;
}
