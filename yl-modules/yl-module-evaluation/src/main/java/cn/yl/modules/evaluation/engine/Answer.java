package cn.yl.modules.evaluation.engine;

import lombok.Getter;
import lombok.Setter;

/** 单题作答（indicatorId -> 分值）。同时作为评分请求体的轻量值对象，由 Jackson 反序列化。 */
@Getter
@Setter
public class Answer {

    private int indicatorId;
    private int score;

    public Answer() {}

    public Answer(int indicatorId, int score) {
        this.indicatorId = indicatorId;
        this.score = score;
    }
}
