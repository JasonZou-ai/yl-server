package cn.yl.modules.evaluation.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量作答提交请求。
 *
 * <p>集合字段采用防御性拷贝（getter 返回不可变副本、setter 存拷贝），避免暴露内部可变表示（EI_EXPOSE_REP）。
 */
public class SubmitAnswersRequest {

    private List<AnswerInput> answers = new ArrayList<>();

    public SubmitAnswersRequest() {}

    public SubmitAnswersRequest(List<AnswerInput> answers) {
        this.answers = answers == null ? new ArrayList<>() : new ArrayList<>(answers);
    }

    public List<AnswerInput> getAnswers() {
        return List.copyOf(answers);
    }

    public void setAnswers(List<AnswerInput> answers) {
        this.answers = answers == null ? new ArrayList<>() : new ArrayList<>(answers);
    }
}
