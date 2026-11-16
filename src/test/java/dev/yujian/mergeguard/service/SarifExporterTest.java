package dev.yujian.mergeguard.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.yujian.mergeguard.domain.Finding;
import dev.yujian.mergeguard.domain.Review;
import dev.yujian.mergeguard.domain.Severity;
import dev.yujian.mergeguard.domain.Verdict;
import java.util.List;
import org.junit.jupiter.api.Test;

class SarifExporterTest {

    @Test
    void exportsValidSarifShape() {
        Review review = new Review();
        review.setHeadSha("abc123");
        review.setVerdict(Verdict.BLOCK);
        review.setScore(10);
        Finding finding = new Finding();
        finding.setFilePath("src/main/java/App.java");
        finding.setLineNumber(12);
        finding.setSeverity(Severity.BLOCKER);
        finding.setCategory("SECURITY");
        finding.setTitle("SQL injection");
        finding.setSuggestion("Use parameters");
        finding.setSource(dev.yujian.mergeguard.domain.FindingSource.RULE);
        finding.setConfidence(1.0);
        finding.setRuleId("R-SEC-02");
        var sarif = new SarifExporter().export(review, List.of(finding));
        assertThat(sarif).containsKeys("$schema", "version", "runs");
        assertThat(sarif.get("version")).isEqualTo("2.1.0");
    }
}
