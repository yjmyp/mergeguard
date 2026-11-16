package dev.yujian.mergeguard.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import dev.yujian.mergeguard.domain.FindingSource;
import dev.yujian.mergeguard.domain.Severity;
import dev.yujian.mergeguard.domain.Verdict;
import java.util.List;
import org.junit.jupiter.api.Test;

class RiskAggregatorTest {

    private final RiskAggregator aggregator = new RiskAggregator();

    private static FindingDraft d(String file, int line, Severity severity, String category, String ruleId) {
        return FindingDraft.rule(file, line, severity, category, ruleId, "title", "evidence", "fix");
    }

    @Test
    void emptyFindingsShipWithFullScore() {
        var result = aggregator.aggregate(List.of(), 40);
        assertThat(result.verdict()).isEqualTo(Verdict.SHIP);
        assertThat(result.score()).isEqualTo(100);
    }

    @Test
    void blockerForcesBlock() {
        var result = aggregator.aggregate(List.of(d("a.sql", 1, Severity.BLOCKER, "DATA_LOSS", "R-SQL-01")), 40);
        assertThat(result.verdict()).isEqualTo(Verdict.BLOCK);
        assertThat(result.score()).isEqualTo(60);
    }

    @Test
    void majorForcesFix() {
        var result = aggregator.aggregate(List.of(d("a.java", 1, Severity.MAJOR, "RELIABILITY", "R-CODE-02")), 40);
        assertThat(result.verdict()).isEqualTo(Verdict.FIX);
    }

    @Test
    void minorOnlyCanStillShip() {
        var result = aggregator.aggregate(List.of(d("a.java", 1, Severity.MINOR, "CONFIG", "R-CFG-01")), 40);
        assertThat(result.verdict()).isEqualTo(Verdict.SHIP);
        assertThat(result.score()).isEqualTo(96);
    }

    @Test
    void mergesSameRuleOnSameFile() {
        var first = d("V3.sql", 1, Severity.BLOCKER, "DATA_LOSS", "R-SQL-01");
        var second = new FindingDraft("V3.sql", 3, Severity.BLOCKER, "DATA_LOSS",
                "title", "DROP TABLE x;", "fix", FindingSource.RULE, "R-SQL-01", 1.0);
        var result = aggregator.aggregate(List.of(first, second), 40);
        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().get(0).evidence()).contains("evidence").contains("DROP TABLE x;");
    }

    @Test
    void dropsLlmFindingNearRuleFinding() {
        var rule = d("Refund.java", 12, Severity.BLOCKER, "SECURITY", "R-SEC-01");
        var llm = FindingDraft.llm("Refund.java", 13, Severity.BLOCKER, "SECURITY", "hardcoded key", "e", "s", 0.9);
        var result = aggregator.aggregate(List.of(rule, llm), 40);
        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().get(0).source()).isEqualTo(FindingSource.RULE);
    }

    @Test
    void capsFindings() {
        List<FindingDraft> many = java.util.stream.IntStream.range(0, 50)
                .mapToObj(i -> d("f" + i, i, Severity.MINOR, "CONFIG", "R-X"))
                .toList();
        assertThat(aggregator.aggregate(many, 10).findings()).hasSize(10);
    }
}
