package cn.yl.modules.evaluation.domain;

/**
 * 评估域保留期口径（ER-03）。
 *
 * <p>单一真源：评估记录与报告的 {@code retain_until} 由 {@code publish} 写入、由归档任务消费，写读两侧必须同口径，
 * 否则会出现「永远到不了期」或「过早归档」两类静默缺陷（无异常、只能在数据里发现）。
 */
public final class RetentionPolicy {

    /** 评估记录/报告保留年限（ER-03：评估记录 5 年）。 */
    public static final int RECORD_RETENTION_YEARS = 5;

    private RetentionPolicy() {}
}
