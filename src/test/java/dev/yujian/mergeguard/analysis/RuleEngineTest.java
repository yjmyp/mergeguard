package dev.yujian.mergeguard.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import dev.yujian.mergeguard.diff.FileDiff;
import dev.yujian.mergeguard.domain.Severity;
import dev.yujian.mergeguard.github.GhFile;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleEngineTest {

    private final RuleEngine engine = new RuleEngine();

    private static GhFile file(String name, String... addedLines) {
        StringBuilder patch = new StringBuilder("@@ -1,1 +1,").append(Math.max(1, addedLines.length)).append(" @@\n");
        for (String line : addedLines) {
            patch.append("+").append(line).append("\n");
        }
        return new GhFile(name, "modified", addedLines.length, 0, addedLines.length, patch.toString(), null);
    }

    private static GhFile bigFile(String name, int additions) {
        return new GhFile(name, "added", additions, 0, additions, null, null);
    }

    private List<FindingDraft> analyze(GhFile... files) {
        return engine.analyze(java.util.Arrays.stream(files).map(FileDiff::parse).toList());
    }

    private boolean has(List<FindingDraft> findings, String ruleId) {
        return findings.stream().anyMatch(f -> ruleId.equals(f.ruleId()));
    }

    @Test
    void detectsAwsKey() {
        var findings = analyze(file("Config.java", "private static final String KEY = \"AKIAZZ7Q3KJ9QW2N4M8P\";"));
        assertThat(has(findings, "R-SEC-01")).isTrue();
        assertThat(findings.get(0).severity()).isEqualTo(Severity.BLOCKER);
    }

    @Test
    void ignoresPlaceholders() {
        var findings = analyze(file("Config.java", "String password = \"${DB_PASSWORD}\";"));
        assertThat(findings).isEmpty();
    }

    @Test
    void detectsPrivateKey() {
        var findings = analyze(file("id_rsa", "-----BEGIN OPENSSH PRIVATE KEY-----"));
        assertThat(has(findings, "R-SEC-01")).isTrue();
    }

    @Test
    void detectsDestructiveSql() {
        var findings = analyze(file("V3__drop.sql", "DROP TABLE refund_audit;", "ALTER TABLE orders DROP COLUMN legacy;"));
        assertThat(has(findings, "R-SQL-01")).isTrue();
        assertThat(findings.get(0).severity()).isEqualTo(Severity.BLOCKER);
    }

    @Test
    void detectsDeleteWithoutWhere() {
        var findings = analyze(file("cleanup.sql", "DELETE FROM orders;"));
        assertThat(has(findings, "R-SQL-02")).isTrue();
    }

    @Test
    void detectsSqlInjection() {
        var findings = analyze(file("Dao.java",
                "String sql = \"SELECT * FROM users WHERE id = \" + id;"));
        assertThat(has(findings, "R-SEC-02")).isTrue();
    }

    @Test
    void detectsCommandInjection() {
        var findings = analyze(file("Run.java", "Runtime.getRuntime().exec(command);"));
        assertThat(has(findings, "R-SEC-03")).isTrue();
    }

    @Test
    void detectsDisabledTls() {
        var findings = analyze(file("client.py", "requests.get(url, verify=False)"));
        assertThat(has(findings, "R-SEC-04")).isTrue();
    }

    @Test
    void detectsDebugOutput() {
        var findings = analyze(file("Service.java", "System.out.println(\"debug\");"));
        assertThat(has(findings, "R-CODE-01")).isTrue();
        assertThat(findings.get(0).severity()).isEqualTo(Severity.MINOR);
    }

    @Test
    void detectsDisabledTest() {
        var findings = analyze(file("FooTest.java", "@Disabled(\"flaky\")"));
        assertThat(has(findings, "R-TEST-02")).isTrue();
        assertThat(findings.get(0).severity()).isEqualTo(Severity.MAJOR);
    }

    @Test
    void detectsEmptyCatch() {
        var findings = analyze(file("Service.java", "try {", "  work();", "} catch (Exception e) {", "}"));
        assertThat(has(findings, "R-CODE-02")).isTrue();
    }

    @Test
    void detectsBroadCatch() {
        var findings = analyze(file("Service.java", "catch (Exception e) { log(e); }"));
        assertThat(has(findings, "R-CODE-03")).isTrue();
    }

    @Test
    void detectsRiskyWorkflow() {
        var findings = analyze(file(".github/workflows/ci.yml", "on: pull_request_target", "permissions: write-all"));
        assertThat(has(findings, "R-CI-01")).isTrue();
        assertThat(has(findings, "R-CI-02")).isTrue();
    }

    @Test
    void detectsInternalUrl() {
        var findings = analyze(file("Config.java", "return \"http://10.0.12.44:8080\";"));
        assertThat(has(findings, "R-CFG-01")).isTrue();
    }

    @Test
    void detectsEval() {
        var findings = analyze(file("main.js", "eval(userInput);"));
        assertThat(has(findings, "R-SEC-05")).isTrue();
    }

    @Test
    void flagsEnvFile() {
        var findings = analyze(new GhFile(".env", "added", 3, 0, 3, null, null));
        assertThat(has(findings, "R-SEC-06")).isTrue();
        assertThat(findings.get(0).severity()).isEqualTo(Severity.BLOCKER);
    }

    @Test
    void flagsMissingTestsForLargeMainChange() {
        String[] lines = new String[70];
        java.util.Arrays.fill(lines, "int value = compute();");
        var findings = analyze(file("src/main/java/App.java", lines));
        assertThat(has(findings, "R-TEST-01")).isTrue();
        assertThat(findings.stream().filter(f -> "R-TEST-01".equals(f.ruleId())).findFirst().orElseThrow()
                .severity()).isEqualTo(Severity.MAJOR);
    }

    @Test
    void doesNotFlagMissingTestsWhenTestsChanged() {
        var findings = analyze(file("src/main/java/App.java", "int value = compute();"),
                file("src/test/java/AppTest.java", "assertThat(value).isPositive();"));
        assertThat(has(findings, "R-TEST-01")).isFalse();
    }

    @Test
    void flagsOversizedPullRequest() {
        var findings = analyze(bigFile("src/main/java/Big.java", 700));
        assertThat(has(findings, "R-PR-01")).isTrue();
        assertThat(findings.stream().filter(f -> "R-PR-01".equals(f.ruleId())).findFirst().orElseThrow()
                .filePath()).isEqualTo("(pull request)");
    }
}
