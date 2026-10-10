package cn.yl.modules.evaluation.engine;

import java.util.List;
import lombok.Getter;

/**
 * 当前生效规则集元信息。
 *
 * <p>报告侧应绑定 {@code ruleVersion} 与 {@code ruleSha}，以支持事后用同一版本规则复算、核对历史判定。
 *
 * <p>{@code dimensions} 下发维度编码/名称/满分，供四端渲染雷达图——维度名称与满分同属服务端权威数据， 四端不内置常量、不本地算分。
 */
@Getter
public class RuleMeta {

    private final String ruleVersion;
    private final String ruleSha;
    private final String status;
    private final String dataKind;
    private final int fullScore;
    private final int levelCount;
    private final int indicatorCount;
    private final List<DimensionMeta> dimensions;

    public RuleMeta(
            String ruleVersion,
            String ruleSha,
            String status,
            String dataKind,
            int fullScore,
            int levelCount,
            int indicatorCount,
            List<DimensionMeta> dimensions) {
        this.ruleVersion = ruleVersion;
        this.ruleSha = ruleSha;
        this.status = status;
        this.dataKind = dataKind;
        this.fullScore = fullScore;
        this.levelCount = levelCount;
        this.indicatorCount = indicatorCount;
        this.dimensions = List.copyOf(dimensions);
    }

    /** 维度元信息（编码 / 名称 / 满分 / 指标数）。 */
    @Getter
    public static class DimensionMeta {
        private final String code;
        private final String name;
        private final int fullScore;
        private final int indicatorCount;

        public DimensionMeta(String code, String name, int fullScore, int indicatorCount) {
            this.code = code;
            this.name = name;
            this.fullScore = fullScore;
            this.indicatorCount = indicatorCount;
        }
    }
}
