package cn.yl.modules.evaluation.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 跨语言等价性测试：Java 引擎 vs Node 权威 oracle 用例库（cases.json）。
 *
 * <p>cases.json 由 grading-engine 的 gen-cases（阈值硬编码、不读规则 JSON）独立产出，作为单一事实源基准。 本测试确保 Java 端口与 Node
 * 端对每一条用例的 totalScore / 初判 / 终判 / 等级变化原因逐字段一致。
 */
class GradingEngineEquivalenceTest {

    private RuleSet ruleSet;

    @BeforeEach
    void setUp() throws Exception {
        this.ruleSet = RuleSetLoader.loadFromClasspath("rules/gbt42195-ruleset.json");
        RuleSetValidator.validate(ruleSet);
    }

    @Test
    void engineMatchesOracleCases() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in =
                GradingEngineEquivalenceTest.class.getResourceAsStream("/grading/cases.json")) {
            Assertions.assertNotNull(in, "oracle 用例库未找到: /grading/cases.json");
            JsonNode root = mapper.readTree(in);
            int pass = 0;
            List<String> failures = new ArrayList<>();
            for (JsonNode c : root) {
                List<Answer> answers = new ArrayList<>();
                JsonNode ans = c.get("input").get("answers");
                for (JsonNode a : ans) {
                    answers.add(new Answer(a.get("indicatorId").asInt(), a.get("score").asInt()));
                }
                SpecialFlags flags = parseFlags(c.get("input").get("flags"));
                GradeResult r = GradingEngine.grade(ruleSet, answers, flags);
                JsonNode exp = c.get("expect");

                List<String> det = new ArrayList<>();
                check("totalScore", r.getTotalScore(), exp.get("totalScore").asInt(), det);
                check(
                        "preliminaryLevel",
                        r.getPreliminaryLevel(),
                        exp.get("preliminaryLevel").asInt(),
                        det);
                check("level", r.getLevel(), exp.get("level").asInt(), det);
                String expReason =
                        exp.get("levelChangeReason").isNull()
                                ? null
                                : exp.get("levelChangeReason").asText();
                if (!Objects.equals(r.getLevelChangeReason(), expReason)) {
                    det.add(
                            "levelChangeReason: got "
                                    + r.getLevelChangeReason()
                                    + " want "
                                    + expReason);
                }

                if (det.isEmpty()) {
                    pass++;
                } else {
                    failures.add(
                            "["
                                    + c.get("id").asText()
                                    + "] "
                                    + c.get("title").asText()
                                    + ": "
                                    + String.join("; ", det));
                }
            }
            if (!failures.isEmpty()) {
                Assertions.fail(
                        "等价性失败 "
                                + failures.size()
                                + "/"
                                + root.size()
                                + "\n"
                                + String.join("\n", failures));
            }
            Assertions.assertEquals(root.size(), pass, "通过数应与用例总数一致");
        }
    }

    @Test
    void ruleVersionAndShaAreBound() {
        Assertions.assertEquals("1.0.0", ruleSet.getRuleVersion());
        Assertions.assertNotNull(ruleSet.getRuleSha());
        Assertions.assertFalse(ruleSet.getRuleSha().isEmpty());
    }

    @Test
    void dimScoresSumEqualsTotal() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in =
                GradingEngineEquivalenceTest.class.getResourceAsStream("/grading/cases.json")) {
            Assertions.assertNotNull(in, "oracle 用例库未找到: /grading/cases.json");
            JsonNode root = mapper.readTree(in);
            for (JsonNode c : root) {
                List<Answer> answers = new ArrayList<>();
                for (JsonNode a : c.get("input").get("answers")) {
                    answers.add(new Answer(a.get("indicatorId").asInt(), a.get("score").asInt()));
                }
                GradeResult r =
                        GradingEngine.grade(
                                ruleSet, answers, parseFlags(c.get("input").get("flags")));
                int sum = r.getDimScores().values().stream().mapToInt(Integer::intValue).sum();
                Assertions.assertEquals(
                        r.getTotalScore(), sum, "维度分之和应等于总分: " + c.get("id").asText());
            }
        }
    }

    private static SpecialFlags parseFlags(JsonNode flagsNode) {
        SpecialFlags f = new SpecialFlags();
        if (flagsNode == null || flagsNode.isNull()) {
            return f;
        }
        if (flagsNode.has("coma")) {
            f.setComa(flagsNode.get("coma").asBoolean());
        }
        if (flagsNode.has("dementia")) {
            f.setDementia(flagsNode.get("dementia").asBoolean());
        }
        if (flagsNode.has("mentalDisorder")) {
            f.setMentalDisorder(flagsNode.get("mentalDisorder").asBoolean());
        }
        if (flagsNode.has("riskEventCount")) {
            f.setRiskEventCount(flagsNode.get("riskEventCount").asInt());
        }
        return f;
    }

    private static void check(String name, int got, int want, List<String> det) {
        if (got != want) {
            det.add(name + ": got " + got + " want " + want);
        }
    }
}
