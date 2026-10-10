package cn.yl.modules.archive.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 能力变化趋势点（D-4 家属端 {@code GET /family/bindings/{relationId}/trend}，published_at 升序）。 */
@Data
public class FamilyTrendPointView {

    private Long reportId;

    private LocalDateTime publishedAt;

    private String gradeCode;

    /** 等级名称（优先终判，缺失回退初判名）。 */
    private String levelName;

    private BigDecimal totalScore;
}
