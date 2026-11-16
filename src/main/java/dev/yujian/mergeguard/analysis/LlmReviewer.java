package dev.yujian.mergeguard.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.yujian.mergeguard.config.MergeGuardProperties;
import dev.yujian.mergeguard.diff.FileDiff;
import dev.yujian.mergeguard.domain.Severity;
import dev.yujian.mergeguard.github.GhPullRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class LlmReviewer {

    private static final Logger log = LoggerFactory.getLogger(LlmReviewer.class);
    private static final Set<String> CATEGORIES = Set.of(
            "SECURITY", "DATA_LOSS", "RELIABILITY", "CORRECTNESS", "TESTING",
            "PERFORMANCE", "MAINTAINABILITY", "CONFIG", "CI_SECURITY", "REVIEW_COST");

    private static final String SYSTEM_PROMPT = """
            你是资深代码评审员，负责判断一个 PR 在合并前是否会造成真实风险。
            只报告会导致安全、数据、正确性、可靠性或测试缺口的问题；不要报告命名、格式、注释等风格问题。
            每条问题必须能定位到 diff 中新增的某一行（或明确是文件级问题），并给出可执行的修复建议。
            输出严格 JSON：{"summary":"不超过120字的中文结论","findings":[{"file":"路径","line":行号或null,"severity":"blocker|major|minor|info","category":"SECURITY|DATA_LOSS|RELIABILITY|CORRECTNESS|TESTING|PERFORMANCE|MAINTAINABILITY|CONFIG|CI_SECURITY|REVIEW_COST","title":"不超过40字","evidence":"代码证据，不超过200字","suggestion":"修复建议，不超过200字","confidence":0到1}]}
            没有真实风险时 findings 返回空数组。不要编造未出现在 diff 中的文件或行号。
            """;

    private final RestClient llmRestClient;
    private final MergeGuardProperties properties;
    private final ObjectMapper objectMapper;

    public LlmReviewer(RestClient llmRestClient, MergeGuardProperties properties, ObjectMapper objectMapper) {
        this.llmRestClient = llmRestClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public LlmResult review(String repoFullName, GhPullRequest pr, List<FileDiff> diffs) {
        long start = System.currentTimeMillis();
        MergeGuardProperties.Llm config = properties.getLlm();
        if (!config.isEnabled()) {
            return LlmResult.skipped("LLM disabled");
        }
        String apiKey = config.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return LlmResult.skipped("LLM api key not configured");
        }
        String userPrompt = buildPrompt(repoFullName, pr, diffs);
        if (userPrompt == null) {
            return LlmResult.skipped("diff too large or empty");
        }
        try {
            Map<String, Object> response = call(config, userPrompt, true);
            String content = extractContent(response);
            Parsed parsed = parse(content, addedLineMap(diffs));
            int inputTokens = intValue(nested(response, "usage", "prompt_tokens"));
            int outputTokens = intValue(nested(response, "usage", "completion_tokens"));
            return new LlmResult(parsed.summary(), parsed.findings(), config.getModel(), inputTokens, outputTokens,
                    System.currentTimeMillis() - start, null);
        } catch (Exception first) {
            log.warn("LLM review failed, retrying without response_format: {}", first.getMessage());
            try {
                Map<String, Object> response = call(config, userPrompt, false);
                String content = extractContent(response);
                Parsed parsed = parse(content, addedLineMap(diffs));
                int inputTokens = intValue(nested(response, "usage", "prompt_tokens"));
                int outputTokens = intValue(nested(response, "usage", "completion_tokens"));
                return new LlmResult(parsed.summary(), parsed.findings(), config.getModel(), inputTokens, outputTokens,
                        System.currentTimeMillis() - start, null);
            } catch (Exception second) {
                log.warn("LLM review failed: {}", second.getMessage());
                return new LlmResult("", List.of(), config.getModel(), 0, 0,
                        System.currentTimeMillis() - start, truncate(second.getMessage(), 300));
            }
        }
    }

    private Map<String, Object> call(MergeGuardProperties.Llm config, String userPrompt, boolean jsonMode) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", config.getModel());
        body.put("temperature", 0.1);
        body.put("max_tokens", 2000);
        body.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", userPrompt)));
        if (jsonMode) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        return llmRestClient.post()
                .uri("/chat/completions")
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new IllegalStateException("LLM HTTP " + response.getStatusCode().value());
                })
                .body(Map.class);
    }

    private String buildPrompt(String repo, GhPullRequest pr, List<FileDiff> diffs) {
        StringBuilder builder = new StringBuilder();
        builder.append("仓库：").append(repo).append("\nPR：").append(pr.title()).append("\n");
        if (pr.body() != null && !pr.body().isBlank()) {
            builder.append("描述：").append(truncate(pr.body(), 800)).append("\n");
        }
        builder.append("审查以下新增代码（已按文件分块）：\n");
        int budget = properties.getLlm().getMaxDiffChars();
        for (FileDiff diff : diffs) {
            if (diff.addedLines().isEmpty()) {
                continue;
            }
            StringBuilder block = new StringBuilder("### ").append(diff.filename()).append("\n");
            int perFile = 0;
            for (FileDiff.AddedLine line : diff.addedLines()) {
                String text = "+ " + line.content();
                if (perFile + text.length() > 2600) {
                    block.append("... (truncated)\n");
                    break;
                }
                block.append(text).append("\n");
                perFile += text.length();
            }
            if (budget - builder.length() - block.length() <= 0) {
                builder.append("... (remaining files truncated)\n");
                break;
            }
            builder.append(block);
        }
        return builder.length() > 200 ? builder.toString() : null;
    }

    public Parsed parse(String content, Map<String, Set<Integer>> addedLines) {
        if (content == null || content.isBlank()) {
            return new Parsed("", List.of());
        }
        String cleaned = content.trim();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return new Parsed("", List.of());
        }
        try {
            JsonNode root = objectMapper.readTree(cleaned.substring(start, end + 1));
            String summary = truncate(root.path("summary").asText(""), 300);
            List<FindingDraft> findings = new ArrayList<>();
            JsonNode array = root.path("findings");
            if (array.isArray()) {
                for (JsonNode node : array) {
                    String file = node.path("file").asText("");
                    if (!addedLines.containsKey(file)) {
                        continue;
                    }
                    Integer line = node.hasNonNull("line") ? node.get("line").asInt() : null;
                    if (line != null && !addedLines.get(file).contains(line)) {
                        line = null;
                    }
                    String category = node.path("category").asText("CORRECTNESS").toUpperCase();
                    if (!CATEGORIES.contains(category)) {
                        category = "CORRECTNESS";
                    }
                    String title = truncate(node.path("title").asText(""), 80);
                    if (title.isBlank()) {
                        continue;
                    }
                    findings.add(FindingDraft.llm(file, line,
                            Severity.from(node.path("severity").asText("major"), Severity.MAJOR),
                            category, title,
                            truncate(node.path("evidence").asText(""), 400),
                            truncate(node.path("suggestion").asText(""), 400),
                            Math.max(0, Math.min(1, node.path("confidence").asDouble(0.7)))));
                }
            }
            return new Parsed(summary, findings);
        } catch (Exception e) {
            log.warn("Cannot parse LLM JSON: {}", e.getMessage());
            return new Parsed("", List.of());
        }
    }

    private Map<String, Set<Integer>> addedLineMap(List<FileDiff> diffs) {
        Map<String, Set<Integer>> map = new HashMap<>();
        for (FileDiff diff : diffs) {
            map.put(diff.filename(), new HashSet<>(diff.addedLineNumbers()));
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private String extractContent(Map<String, Object> response) {
        if (response == null) return "";
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        if (choices == null || choices.isEmpty()) return "";
        Object message = choices.get(0).get("message");
        if (message instanceof Map<?, ?> map) {
            Object content = map.get("content");
            return content == null ? "" : content.toString();
        }
        return "";
    }

    private Object nested(Map<String, Object> response, String outer, String inner) {
        if (response == null) return null;
        Object node = response.get(outer);
        if (node instanceof Map<?, ?> map) {
            return map.get(inner);
        }
        return null;
    }

    private int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max);
    }

    public record Parsed(String summary, List<FindingDraft> findings) {
    }

    public record LlmResult(String summary, List<FindingDraft> findings, String model,
                            int inputTokens, int outputTokens, long latencyMs, String error) {
        public static LlmResult skipped(String reason) {
            return new LlmResult("", List.of(), null, 0, 0, 0, reason);
        }

        public boolean used() { return error == null; }
    }
}
