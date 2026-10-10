package cn.yl.modules.evaluation.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 题库下发响应：26 项二级指标元信息 + 所属规则版本指纹。
 *
 * <p>四端应缓存本响应，并在 {@code ruleVersion}/{@code ruleSha} 变化时重新拉取；提交作答时直接携带 {@code itemId}，端上不做题号换算。
 */
public class ItemBankResponse {

    private String ruleVersion;
    private String ruleSha;
    private List<EvalItemDto> items = new ArrayList<>();

    public String getRuleVersion() {
        return ruleVersion;
    }

    public void setRuleVersion(String ruleVersion) {
        this.ruleVersion = ruleVersion;
    }

    public String getRuleSha() {
        return ruleSha;
    }

    public void setRuleSha(String ruleSha) {
        this.ruleSha = ruleSha;
    }

    /** 返回不可变副本，避免外部改写内部集合（EI_EXPOSE_REP）。 */
    public List<EvalItemDto> getItems() {
        return List.copyOf(items);
    }

    /** 存储拷贝，避免持有外部可变引用（EI_EXPOSE_REP2）。 */
    public void setItems(List<EvalItemDto> items) {
        this.items = items == null ? new ArrayList<>() : new ArrayList<>(items);
    }
}
