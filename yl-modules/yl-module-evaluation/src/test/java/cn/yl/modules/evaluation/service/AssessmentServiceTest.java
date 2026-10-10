package cn.yl.modules.evaluation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.yl.common.exception.BizException;
import cn.yl.modules.evaluation.domain.entity.EvalAnswer;
import cn.yl.modules.evaluation.domain.entity.EvalItem;
import cn.yl.modules.evaluation.domain.entity.EvalOrder;
import cn.yl.modules.evaluation.domain.entity.GbRuleVersion;
import cn.yl.modules.evaluation.dto.AnswerInput;
import cn.yl.modules.evaluation.dto.AssessmentResultResponse;
import cn.yl.modules.evaluation.dto.CreateAssessmentRequest;
import cn.yl.modules.evaluation.dto.ItemBankResponse;
import cn.yl.modules.evaluation.dto.SubmitAnswersRequest;
import cn.yl.modules.evaluation.dto.SubmitAssessmentRequest;
import cn.yl.modules.evaluation.mapper.EvalAnswerMapper;
import cn.yl.modules.evaluation.mapper.EvalItemMapper;
import cn.yl.modules.evaluation.mapper.EvalOrderMapper;
import cn.yl.modules.evaluation.mapper.EvalReviewLogMapper;
import cn.yl.modules.evaluation.mapper.EvalTaskMapper;
import cn.yl.modules.evaluation.mapper.GbRuleVersionMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 评估中心服务测试（C1）：验证「真实业务调用引擎」的闭环。
 *
 * <p>持久化用 Mock，分级引擎用<b>真实</b>实现（加载 classpath 权威 RuleSet），因此本测能同时覆盖：
 * DTO→引擎入参映射、引擎判定、等级码翻译、结果回写与流转日志。
 *
 * <p>26 项满分合计 90（＝规则集 fullScore），故全对时应判 L0 / GRADE_INTACT。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssessmentServiceTest {

    /** 26 项指标 id 与其满分（来源：权威规则集 indicators）。 */
    private static final int[][] ITEMS = {
        {101, 4}, {102, 4}, {103, 4}, {104, 4}, {105, 4}, {106, 4}, {107, 4}, {108, 4}, {201, 4},
        {202, 4}, {203, 4}, {204, 3}, {301, 4}, {302, 4}, {303, 4}, {304, 4}, {305, 4}, {306, 4},
        {307, 1}, {308, 1}, {309, 2}, {401, 2}, {402, 2}, {403, 4}, {404, 3}, {405, 4}
    };

    @Mock private EvalTaskMapper taskMapper;
    @Mock private EvalOrderMapper orderMapper;
    @Mock private EvalAnswerMapper answerMapper;
    @Mock private EvalReviewLogMapper logMapper;
    @Mock private EvalItemMapper itemMapper;
    @Mock private GbRuleVersionMapper ruleVersionMapper;

    private AssessmentService service;

    @BeforeEach
    void setUp() {
        AssessmentGradingService grading = new AssessmentGradingService();
        service =
                new AssessmentService(
                        taskMapper,
                        orderMapper,
                        answerMapper,
                        logMapper,
                        itemMapper,
                        ruleVersionMapper,
                        grading);
    }

    @Test
    void submitWithFullScoresPersistsGradeIntact() {
        Long orderId = 9001L;
        when(orderMapper.selectById(orderId)).thenReturn(draftOrder(orderId));
        when(answerMapper.selectList(any(QueryWrapper.class))).thenReturn(fullAnswers());
        when(itemMapper.selectList(any(QueryWrapper.class))).thenReturn(requiredItems());
        when(ruleVersionMapper.selectOne(any(QueryWrapper.class))).thenReturn(ruleVersion());

        AssessmentResultResponse resp = service.submit(orderId, new SubmitAssessmentRequest());

        assertEquals(90, resp.getTotalScore().intValue());
        assertEquals("GRADE_INTACT", resp.getGradeCode());
        assertEquals("L0", resp.getPreliminaryLevelCode());
        assertEquals("L0", resp.getLevelCode());
        assertEquals("SUBMITTED", resp.getStatus());
        assertEquals("1.0.0", resp.getRuleVersion());
        assertNotNull(resp.getRuleSha());
        assertEquals(Integer.valueOf(0), resp.getGradeUpgraded());

        ArgumentCaptor<EvalOrder> captor = ArgumentCaptor.forClass(EvalOrder.class);
        verify(orderMapper).updateById(captor.capture());
        assertEquals("GRADE_INTACT", captor.getValue().getGradeCode());
        assertEquals("SUBMITTED", captor.getValue().getStatus());
        verify(logMapper).insert(any());
    }

    @Test
    void submitWithDementiaEscalatesToMild() {
        Long orderId = 9002L;
        when(orderMapper.selectById(orderId)).thenReturn(draftOrder(orderId));
        when(answerMapper.selectList(any(QueryWrapper.class))).thenReturn(fullAnswers());
        when(itemMapper.selectList(any(QueryWrapper.class))).thenReturn(requiredItems());
        when(ruleVersionMapper.selectOne(any(QueryWrapper.class))).thenReturn(ruleVersion());

        SubmitAssessmentRequest req = new SubmitAssessmentRequest();
        req.setDementia(true);

        AssessmentResultResponse resp = service.submit(orderId, req);

        // 初判 L0（满分 90），因确诊痴呆上调一级 → 终判 L1
        assertEquals("L0", resp.getPreliminaryLevelCode());
        assertEquals("L1", resp.getLevelCode());
        assertEquals("GRADE_MILD", resp.getGradeCode());
        assertEquals(Integer.valueOf(1), resp.getGradeUpgraded());
        assertNotNull(resp.getLevelChangeReason());
    }

    @Test
    void submitBlockedWhenRequiredItemMissing() {
        Long orderId = 9003L;
        when(orderMapper.selectById(orderId)).thenReturn(draftOrder(orderId));
        List<EvalAnswer> partial = fullAnswers();
        partial.remove(0); // 去掉 101 进食
        when(answerMapper.selectList(any(QueryWrapper.class))).thenReturn(partial);
        when(itemMapper.selectList(any(QueryWrapper.class))).thenReturn(requiredItems());

        assertThrows(
                BizException.class, () -> service.submit(orderId, new SubmitAssessmentRequest()));
    }

    /**
     * 昏迷直判路径豁免 ER-06 必答校验（真机联调暴露的缺陷回归）。
     *
     * <p>GB/T 42195-2022 表6 说明1：昏迷即直判能力完全丧失，无需逐项录入。 此前 {@code submit} 无条件校验必答，导致「昏迷老人被要求填满 26
     * 项才能提交」； 无状态 {@code POST /grade} 不受影响，故 Mock/单测阶段未暴露。
     */
    @Test
    void submitSkipsRequiredCheckWhenComa() {
        Long orderId = 9004L;
        when(orderMapper.selectById(orderId)).thenReturn(draftOrder(orderId));
        when(answerMapper.selectList(any(QueryWrapper.class))).thenReturn(new ArrayList<>());

        SubmitAssessmentRequest req = new SubmitAssessmentRequest();
        req.setComa(true);
        AssessmentResultResponse resp = service.submit(orderId, req);

        assertEquals("GRADE_TOTAL", resp.getGradeCode());
    }

    @Test
    void gradeCodeTranslatorCoversAllLevels() {
        assertEquals("GRADE_INTACT", GradeCodeTranslator.toGradeCode("L0"));
        assertEquals("GRADE_MILD", GradeCodeTranslator.toGradeCode("L1"));
        assertEquals("GRADE_MODERATE", GradeCodeTranslator.toGradeCode("L2"));
        assertEquals("GRADE_SEVERE", GradeCodeTranslator.toGradeCode("L3"));
        assertEquals("GRADE_TOTAL", GradeCodeTranslator.toGradeCode("L4"));
        assertThrows(IllegalArgumentException.class, () -> GradeCodeTranslator.toGradeCode("L9"));
    }

    @Test
    void dimensionCodeDerivedFromItemId() {
        assertEquals(1, GradeCodeTranslator.toDimensionCode(101));
        assertEquals(2, GradeCodeTranslator.toDimensionCode(204));
        assertEquals(3, GradeCodeTranslator.toDimensionCode(309));
        assertEquals(4, GradeCodeTranslator.toDimensionCode(405));
    }

    @Test
    void saveAnswersThenOrderStaysDraft() {
        Long orderId = 9004L;
        when(orderMapper.selectById(orderId)).thenReturn(draftOrder(orderId));
        when(answerMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(answerMapper.selectById(anyLong())).thenReturn(null);

        int saved = service.saveAnswers(orderId, new SubmitAnswersRequest(answerInputs()));
        assertEquals(ITEMS.length, saved);
        verify(answerMapper, times(ITEMS.length)).insert(any());
    }

    /**
     * 回归：eval_order.rule_version_id 为 NOT NULL 且无默认值。
     *
     * <p>真实 MySQL 下，创建评估单即 INSERT，若未绑定规则版本会直接触发 NOT NULL 违例； 而 Mock Mapper 不会校验列约束，故此处用断言固定该行为。
     */
    @Test
    void createBindsRuleVersionBeforeInsert() {
        when(ruleVersionMapper.selectOne(any(QueryWrapper.class))).thenReturn(ruleVersion());

        CreateAssessmentRequest req = new CreateAssessmentRequest();
        req.setElderId(1L);
        req.setOrgId(1L);
        req.setAssessorId(2L);
        service.create(req);

        ArgumentCaptor<EvalOrder> captor = ArgumentCaptor.forClass(EvalOrder.class);
        verify(orderMapper).insert(captor.capture());
        EvalOrder inserted = captor.getValue();
        assertNotNull(inserted.getRuleVersionId(), "创建评估单必须绑定生效规则版本（NOT NULL 列）");
        assertEquals(Long.valueOf(1L), inserted.getRuleVersionId());
        assertEquals("DRAFT", inserted.getStatus());
    }

    /**
     * 题库下发的契约：itemId 必须与引擎指标编号一致（101~405），并绑定规则版本指纹。
     *
     * <p>四端据此免除端上题号硬编码映射，故此处固定该契约，防止题库口径漂移。
     */
    @Test
    void itemsMatchEngineIndicatorIds() {
        when(itemMapper.selectList(any(QueryWrapper.class))).thenReturn(itemBankRows());

        ItemBankResponse resp = service.items();

        assertEquals("1.0.0", resp.getRuleVersion());
        assertNotNull(resp.getRuleSha());
        assertEquals(26, resp.getItems().size());
        assertEquals(Long.valueOf(101L), resp.getItems().get(0).getItemId());
        assertEquals(Integer.valueOf(1), resp.getItems().get(0).getDimensionCode());
        assertEquals(Integer.valueOf(4), resp.getItems().get(25).getDimensionCode());
        assertEquals(Long.valueOf(405L), resp.getItems().get(25).getItemId());
        assertEquals(Integer.valueOf(4), resp.getItems().get(0).getWeight());
        assertEquals(Integer.valueOf(1), resp.getItems().get(0).getRequired());
    }

    private List<EvalItem> itemBankRows() {
        List<EvalItem> list = new ArrayList<>();
        int order = 1;
        for (int[] item : ITEMS) {
            EvalItem e = new EvalItem();
            e.setId((long) item[0]);
            e.setDimensionCode(item[0] / 100);
            e.setItemName("指标" + item[0]);
            e.setScoreMax(BigDecimal.valueOf(4));
            e.setOrderNo(order++);
            e.setIsRequired(1);
            list.add(e);
        }
        return list;
    }

    private EvalOrder draftOrder(Long id) {
        EvalOrder order = new EvalOrder();
        order.setId(id);
        order.setOrderNo("EO-TEST");
        order.setTaskId(null);
        order.setStatus("DRAFT");
        order.setAssessorId(1L);
        return order;
    }

    private List<EvalAnswer> fullAnswers() {
        List<EvalAnswer> list = new ArrayList<>();
        for (int[] item : ITEMS) {
            EvalAnswer a = new EvalAnswer();
            a.setItemId((long) item[0]);
            a.setScore(BigDecimal.valueOf(item[1]));
            a.setIsRefused(0);
            a.setAnswerState(1);
            list.add(a);
        }
        return list;
    }

    private List<EvalItem> requiredItems() {
        List<EvalItem> list = new ArrayList<>();
        for (int[] item : ITEMS) {
            EvalItem e = new EvalItem();
            e.setId((long) item[0]);
            e.setItemCode(String.valueOf(item[0]));
            e.setIsRequired(1);
            list.add(e);
        }
        return list;
    }

    private List<AnswerInput> answerInputs() {
        List<AnswerInput> list = new ArrayList<>();
        for (int[] item : ITEMS) {
            AnswerInput in = new AnswerInput();
            in.setItemId((long) item[0]);
            in.setScore(BigDecimal.valueOf(item[1]));
            in.setRefused(false);
            list.add(in);
        }
        return list;
    }

    private GbRuleVersion ruleVersion() {
        GbRuleVersion v = new GbRuleVersion();
        v.setId(1L);
        v.setStandardCode("GB/T 42195-2022");
        v.setVersion("1.0.0");
        v.setStatus(1);
        return v;
    }
}
