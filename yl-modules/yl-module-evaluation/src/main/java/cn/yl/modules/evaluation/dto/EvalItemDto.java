package cn.yl.modules.evaluation.dto;

/**
 * 题库项（服务端权威元信息）。
 *
 * <p>{@code itemId} 即 GB/T 42195-2022 二级指标编号（101~405），与分级引擎的指标 id 完全一致， 因此四端提交作答时可直接使用该
 * id，无需在端上维护「本地题号 → 指标 id」的硬编码映射。
 *
 * <p>选项文本与大白话注释属于端上语料（内容运营范畴，未入库），服务端只下发判定所必需的元信息。
 */
public class EvalItemDto {

    private Long itemId;
    private Integer dimensionCode;
    private String indicatorName;
    private Integer weight;
    private Integer orderNo;
    private Integer required;

    public EvalItemDto() {}

    public EvalItemDto(
            Long itemId,
            Integer dimensionCode,
            String indicatorName,
            Integer weight,
            Integer orderNo,
            Integer required) {
        this.itemId = itemId;
        this.dimensionCode = dimensionCode;
        this.indicatorName = indicatorName;
        this.weight = weight;
        this.orderNo = orderNo;
        this.required = required;
    }

    public Long getItemId() {
        return itemId;
    }

    public void setItemId(Long itemId) {
        this.itemId = itemId;
    }

    public Integer getDimensionCode() {
        return dimensionCode;
    }

    public void setDimensionCode(Integer dimensionCode) {
        this.dimensionCode = dimensionCode;
    }

    public String getIndicatorName() {
        return indicatorName;
    }

    public void setIndicatorName(String indicatorName) {
        this.indicatorName = indicatorName;
    }

    /** 该指标满分（{@code score_max}），四端用于展示权重，不用于本地算分。 */
    public Integer getWeight() {
        return weight;
    }

    public void setWeight(Integer weight) {
        this.weight = weight;
    }

    public Integer getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(Integer orderNo) {
        this.orderNo = orderNo;
    }

    /** 是否必答（1-必答）：未作答将触发 ER-06 提交阻断。 */
    public Integer getRequired() {
        return required;
    }

    public void setRequired(Integer required) {
        this.required = required;
    }
}
