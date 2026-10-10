package cn.yl.modules.evaluation.dto;

import lombok.Data;

/**
 * 照护方案条目。
 *
 * <p>{@code source} 标注来源：{@code TEMPLATE}（按等级匹配到的模板库条目）/ {@code GENERAL}（无匹配模板时的通用建议）/ {@code
 * CUSTOM}（评估员个性化补充），便于事后区分「系统给的」与「人写的」。
 */
@Data
public class CarePlanItem {

    private String title;
    private String content;
    private String source;

    public CarePlanItem() {}

    public CarePlanItem(String title, String content, String source) {
        this.title = title;
        this.content = content;
        this.source = source;
    }
}
