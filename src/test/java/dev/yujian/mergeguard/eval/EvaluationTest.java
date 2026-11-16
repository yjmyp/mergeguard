package dev.yujian.mergeguard.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.yujian.mergeguard.analysis.FindingDraft;
import dev.yujian.mergeguard.analysis.LlmReviewer;
import dev.yujian.mergeguard.analysis.RiskAggregator;
import dev.yujian.mergeguard.analysis.RuleEngine;
import dev.yujian.mergeguard.config.MergeGuardProperties;
import dev.yujian.mergeguard.diff.FileDiff;
import dev.yujian.mergeguard.github.GhFile;
import dev.yujian.mergeguard.github.GhPullRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "mg.eval", matches = "true")
class EvaluationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void runsLabeledBenchmark() throws Exception {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        assertThat(apiKey).as("DEEPSEEK_API_KEY is required for the evaluation run").isNotBlank();

        List<EvalCase> cases = mapper.readValue(Path.of("eval/cases.json").toFile(),
                mapper.getTypeFactory().constructCollectionType(List.class, EvalCase.class));
        MergeGuardProperties properties = new MergeGuardProperties();
        properties.getLlm().setApiKey(apiKey);
        properties.getLlm().setModel(System.getenv().getOrDefault("MERGEGUARD_LLM_MODEL", "deepseek-chat"));
        properties.getLlm().setChunkChars(6000);
        properties.getLlm().setMaxChunks(2);
        properties.getLlm().setPerFileChars(3000);
        properties.getLlm().setMaxFindingsPerChunk(8);

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(60_000);
        RestClient client = RestClient.builder()
                .baseUrl(properties.getLlm().getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .requestFactory(factory)
                .build();

        RuleEngine ruleEngine = new RuleEngine();
        RiskAggregator aggregator = new RiskAggregator();
        LlmReviewer llmReviewer = new LlmReviewer(client, properties, mapper);

        List<CaseResult> results = new ArrayList<>();
        long started = System.currentTimeMillis();
        for (EvalCase evalCase : cases) {
            List<FileDiff> diffs = evalCase.files().stream().map(file -> FileDiff.parse(
                    new GhFile(file.filename(), file.status(), file.additions(), file.deletions(),
                            file.changes(), file.patch(), null))).toList();
            List<FindingDraft> rules = aggregator.aggregate(ruleEngine.analyze(diffs), 40).findings();
            GhPullRequest pr = new GhPullRequest(1, evalCase.title(), "", null, null, null, null);
            LlmReviewer.LlmResult llm = llmReviewer.review("eval/cases", pr, diffs);
            List<FindingDraft> hybrid = aggregator.aggregate(concat(rules, llm.findings()), 40).findings();
            results.add(new CaseResult(evalCase.id(), evalCase.kind(),
                    score(evalCase.expect(), rules), score(evalCase.expect(), hybrid),
                    rules.size(), hybrid.size(), llm.inputTokens() + llm.outputTokens(),
                    llm.latencyMs(), llm.chunks(), llm.coveragePercent(), llm.error(), llm.neutralized(),
                    details(hybrid)));
            System.out.printf("case %-22s rules=%d hybrid=%d tokens=%d%n", evalCase.id(),
                    rules.size(), hybrid.size(), llm.inputTokens() + llm.outputTokens());
        }
        long elapsed = System.currentTimeMillis() - started;

        Report report = buildReport(results, elapsed);
        Files.createDirectories(Path.of("docs"));
        Files.writeString(Path.of("docs/evaluation.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        Files.writeString(Path.of("docs/EVALUATION.md"), renderMarkdown(report, properties.getLlm().getModel()));
        System.out.println("hybrid f1=" + report.hybrid().f1() + " rule f1=" + report.ruleOnly().f1());
    }

    private List<FindingDraft> concat(List<FindingDraft> left, List<FindingDraft> right) {
        List<FindingDraft> all = new ArrayList<>(left);
        all.addAll(right);
        return all;
    }

    private List<String> details(List<FindingDraft> findings) {
        List<String> details = new ArrayList<>();
        for (FindingDraft finding : findings) {
            details.add(finding.category() + "|" + finding.lineNumber() + "|"
                    + String.format(java.util.Locale.ROOT, "%.2f", finding.confidence()) + "|" + finding.title());
        }
        return details;
    }

    private CaseScore score(List<Expectation> expected, List<FindingDraft> predicted) {
        boolean[] matched = new boolean[predicted.size()];
        int tp = 0;
        int lineHits = 0;
        int lineCompared = 0;
        for (Expectation expectation : expected) {
            for (int i = 0; i < predicted.size(); i++) {
                if (matched[i]) continue;
                FindingDraft finding = predicted.get(i);
                boolean fileMatch = finding.filePath().equals(expectation.file());
                boolean categoryMatch = finding.category().equals(expectation.category());
                if (fileMatch && categoryMatch) {
                    matched[i] = true;
                    tp++;
                    if (finding.lineNumber() != null && expectation.line() != null) {
                        lineCompared++;
                        if (Math.abs(finding.lineNumber() - expectation.line()) <= 5) {
                            lineHits++;
                        }
                    }
                    break;
                }
            }
        }
        int fp = 0;
        for (int i = 0; i < matched.length; i++) {
            if (!matched[i]) fp++;
        }
        int fn = expected.size() - tp;
        return new CaseScore(tp, fp, fn, lineHits, lineCompared);
    }

    private Report buildReport(List<CaseResult> results, long elapsed) {
        Aggregates rule = aggregate(results.stream().map(CaseResult::rule).toList());
        Aggregates hybrid = aggregate(results.stream().map(CaseResult::hybrid).toList());
        int tokens = results.stream().mapToInt(CaseResult::tokens).sum();
        long latency = results.stream().mapToLong(CaseResult::latencyMs).sum();
        Map<String, long[]> byCategory = new LinkedHashMap<>();
        for (CaseResult result : results) {
            long[] bucket = byCategory.computeIfAbsent("all", key -> new long[4]);
            bucket[0] += result.hybrid().tp();
            bucket[1] += result.hybrid().fp();
            bucket[2] += result.hybrid().fn();
            bucket[3]++;
        }
        return new Report(Instant.now().toString(), results.size(), tokens, latency, elapsed,
                rule, hybrid, byCategory, results);
    }

    private Aggregates aggregate(List<CaseScore> scores) {
        int tp = scores.stream().mapToInt(CaseScore::tp).sum();
        int fp = scores.stream().mapToInt(CaseScore::fp).sum();
        int fn = scores.stream().mapToInt(CaseScore::fn).sum();
        int lineHits = scores.stream().mapToInt(CaseScore::lineHits).sum();
        int lineCompared = scores.stream().mapToInt(CaseScore::lineCompared).sum();
        double precision = tp + fp == 0 ? 0 : (double) tp / (tp + fp);
        double recall = tp + fn == 0 ? 0 : (double) tp / (tp + fn);
        double f1 = precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall);
        return new Aggregates(tp, fp, fn, precision, recall, f1,
                lineCompared == 0 ? 0 : (double) lineHits / lineCompared, lineHits, lineCompared);
    }

    private String renderMarkdown(Report report, String model) {
        StringBuilder md = new StringBuilder();
        md.append("# MergeGuard 离线评测\n\n");
        md.append("数据集：`eval/cases.json`，").append(report.cases()).append(" 个带标注的 PR 片段（")
                .append(report.cases() - 6).append(" 个风险用例、6 个清洁用例）。\n\n");
        md.append("模型：`").append(model).append("`。生成时间：").append(report.generatedAt())
                .append("。所有数字由 `EvaluationTest` 实际调用模型后写入，不含手工估值。\n\n");
        md.append("## 总体结果\n\n");
        md.append("| 方案 | TP | FP | FN | Precision | Recall | F1 | 行号命中率 |\n|---|---|---|---|---|---|---|---|\n");
        row(md, "规则 only", report.ruleOnly());
        row(md, "规则 + 模型", report.hybrid());
        md.append("\n");
        md.append("- 混合方案比纯规则多召回 **").append(report.hybrid().tp() - report.ruleOnly().tp())
                .append("** 个真实问题。\n");
        md.append("- 混合方案 F1 = **").append(fmt(report.hybrid().f1())).append("**，规则 only F1 = **")
                .append(fmt(report.ruleOnly().f1())).append("**。\n");
        md.append("- 本次调用消耗 **").append(report.tokens()).append("** tokens，模型总耗时 **")
                .append(report.modelLatencyMs()).append(" ms**，整个评测端到端 **").append(report.elapsedMs())
                .append(" ms**。\n\n");
        md.append("## 逐用例\n\n");
        md.append("| 用例 | 类型 | 规则 TP/FP/FN | 混合 TP/FP/FN | tokens | 模型块 | 覆盖 |\n|---|---|---|---|---|---|---|\n");
        for (CaseResult result : report.cases_detail()) {
            md.append("| `").append(result.id()).append("` | ").append(result.kind()).append(" | ")
                    .append(result.rule().tp()).append("/").append(result.rule().fp()).append("/")
                    .append(result.rule().fn()).append(" | ")
                    .append(result.hybrid().tp()).append("/").append(result.hybrid().fp()).append("/")
                    .append(result.hybrid().fn()).append(" | ").append(result.tokens()).append(" | ")
                    .append(result.chunks()).append(" | ").append(result.coverage()).append("% |\n");
        }
        md.append("\n## 失败与误报\n\n");
        for (CaseResult result : report.cases_detail()) {
            if (result.hybrid().fn() > 0) {
                md.append("- `").append(result.id()).append("` 漏报 ").append(result.hybrid().fn()).append(" 项。\n");
            }
            if (result.hybrid().fp() > 0) {
                md.append("- `").append(result.id()).append("` 误报 ").append(result.hybrid().fp()).append(" 项。\n");
            }
            if (result.hybrid().fp() > 0 || result.hybrid().fn() > 0) {
                for (String detail : result.details()) {
                    md.append("  - ").append(detail).append("\n");
                }
            }
        }
        md.append("\n## 复现\n\n```bash\nmvn -q -Dmg.eval=true -Dtest=EvaluationTest test\n```\n");
        return md.toString();
    }

    private void row(StringBuilder md, String name, Aggregates a) {
        md.append("| ").append(name).append(" | ").append(a.tp()).append(" | ").append(a.fp())
                .append(" | ").append(a.fn()).append(" | ").append(fmt(a.precision())).append(" | ")
                .append(fmt(a.recall())).append(" | ").append(fmt(a.f1())).append(" | ")
                .append(fmt(a.lineAccuracy())).append(" |\n");
    }

    private String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    record EvalCase(String id, String kind, String title, List<EvalFile> files, List<Expectation> expect) {
    }

    record EvalFile(String filename, String status, int additions, int deletions, int changes, String patch) {
    }

    record Expectation(String file, String category, Integer line) {
    }

    record CaseScore(int tp, int fp, int fn, int lineHits, int lineCompared) {
    }

    record CaseResult(String id, String kind, CaseScore rule, CaseScore hybrid, int ruleFindings,
                      int hybridFindings, int tokens, long latencyMs, int chunks, int coverage,
                      String llmError, int neutralized, List<String> details) {
    }

    record Aggregates(int tp, int fp, int fn, double precision, double recall, double f1,
                      double lineAccuracy, int lineHits, int lineCompared) {
    }

    record Report(String generatedAt, int cases, int tokens, long modelLatencyMs, long elapsedMs,
                  Aggregates ruleOnly, Aggregates hybrid, Map<String, long[]> byCategory,
                  List<CaseResult> cases_detail) {
    }
}
