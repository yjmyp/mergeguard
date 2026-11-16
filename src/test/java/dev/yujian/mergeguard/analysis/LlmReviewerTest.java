package dev.yujian.mergeguard.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LlmReviewerTest {

    private final LlmReviewer reviewer = new LlmReviewer(null, null, new ObjectMapper());

    @Test
    void parsesStructuredFindings() {
        String json = """
                {"summary":"存在 SQL 注入","findings":[
                  {"file":"Refund.java","line":24,"severity":"blocker","category":"SECURITY",
                   "title":"拼接 SQL","evidence":"query + amount","suggestion":"参数化查询","confidence":0.9}
                ]}
                """;
        var parsed = reviewer.parse(json, Map.of("Refund.java", Set.of(24)));
        assertThat(parsed.summary()).contains("SQL");
        assertThat(parsed.findings()).hasSize(1);
        assertThat(parsed.findings().get(0).lineNumber()).isEqualTo(24);
    }

    @Test
    void acceptsFencedJson() {
        String json = "```json\n{\"summary\":\"ok\",\"findings\":[]}\n```";
        var parsed = reviewer.parse(json, Map.of());
        assertThat(parsed.summary()).isEqualTo("ok");
        assertThat(parsed.findings()).isEmpty();
    }

    @Test
    void dropsUnknownFilesAndLinesNotInDiff() {
        String json = """
                {"summary":"x","findings":[
                  {"file":"unknown.java","line":1,"severity":"major","title":"x"},
                  {"file":"a.java","line":99,"severity":"major","title":"y"}
                ]}
                """;
        var parsed = reviewer.parse(json, Map.of("a.java", Set.of(1)));
        assertThat(parsed.findings()).hasSize(1);
        assertThat(parsed.findings().get(0).filePath()).isEqualTo("a.java");
        assertThat(parsed.findings().get(0).lineNumber()).isNull();
    }

    @Test
    void mapsUnknownCategoryAndSeverity() {
        String json = """
                {"summary":"x","findings":[
                  {"file":"a.java","line":1,"severity":"critical","category":"WEIRD","title":"z"}
                ]}
                """;
        var parsed = reviewer.parse(json, Map.of("a.java", Set.of(1)));
        assertThat(parsed.findings().get(0).category()).isEqualTo("CORRECTNESS");
        assertThat(parsed.findings().get(0).severity().name()).isEqualTo("BLOCKER");
    }

    @Test
    void returnsEmptyOnMalformedJson() {
        var parsed = reviewer.parse("not json", Map.of());
        assertThat(parsed.findings()).isEmpty();
    }

    @Test
    void trimsLongText() {
        String longTitle = "x".repeat(200);
        String json = "{\"summary\":\"" + "s".repeat(500) + "\",\"findings\":[{\"file\":\"a.java\",\"line\":1,\"severity\":\"minor\",\"title\":\"" + longTitle + "\"}]}";
        var parsed = reviewer.parse(json, Map.of("a.java", Set.of(1)));
        assertThat(parsed.summary().length()).isLessThanOrEqualTo(300);
        assertThat(parsed.findings().get(0).title().length()).isLessThanOrEqualTo(80);
    }
}
