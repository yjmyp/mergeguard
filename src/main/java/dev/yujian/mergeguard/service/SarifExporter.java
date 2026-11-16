package dev.yujian.mergeguard.service;

import dev.yujian.mergeguard.domain.Finding;
import dev.yujian.mergeguard.domain.Review;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class SarifExporter {

    public Map<String, Object> export(Review review, List<Finding> findings) {
        Map<String, Object> run = new HashMap<>();
        run.put("tool", Map.of("driver", Map.of(
                "name", "MergeGuard",
                "version", "0.2.0",
                "informationUri", "https://github.com/yjmyp/mergeguard",
                "rules", rules(findings))));
        run.put("results", results(findings));
        Map<String, Object> properties = new HashMap<>();
        properties.put("reviewId", review.getId());
        properties.put("headSha", review.getHeadSha());
        properties.put("verdict", review.getVerdict().name());
        properties.put("score", review.getScore());
        run.put("properties", properties);

        Map<String, Object> root = new HashMap<>();
        root.put("$schema", "https://json.schemastore.org/sarif-2.1.0.json");
        root.put("version", "2.1.0");
        root.put("runs", List.of(run));
        return root;
    }

    private List<Map<String, Object>> rules(List<Finding> findings) {
        Set<String> ids = new LinkedHashSet<>();
        for (Finding finding : findings) {
            ids.add(finding.getRuleId() == null ? "MG-LLM" : finding.getRuleId());
        }
        List<Map<String, Object>> rules = new ArrayList<>();
        for (String id : ids) {
            rules.add(Map.of(
                    "id", id,
                    "shortDescription", Map.of("text", id.startsWith("R-") ? "MergeGuard rule" : "MergeGuard model review")));
        }
        return rules;
    }

    private List<Map<String, Object>> results(List<Finding> findings) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Finding finding : findings) {
            String ruleId = finding.getRuleId() == null ? "MG-LLM" : finding.getRuleId();
            Map<String, Object> region = new HashMap<>();
            if (finding.getLineNumber() != null) {
                region.put("startLine", finding.getLineNumber());
            }
            Map<String, Object> physical = new HashMap<>();
            physical.put("artifactLocation", Map.of("uri", finding.getFilePath()));
            if (!region.isEmpty()) {
                physical.put("region", region);
            }
            Map<String, Object> result = new HashMap<>();
            result.put("ruleId", ruleId);
            result.put("level", level(finding));
            result.put("message", Map.of("text", finding.getTitle() + ". " + finding.getSuggestion()));
            result.put("locations", List.of(Map.of("physicalLocation", physical)));
            result.put("properties", Map.of(
                    "severity", finding.getSeverity().name(),
                    "category", finding.getCategory(),
                    "source", finding.getSource().name(),
                    "confidence", finding.getConfidence()));
            if (finding.getLineNumber() == null) {
                result.put("locations", List.of());
            }
            results.add(result);
        }
        return results;
    }

    private String level(Finding finding) {
        return switch (finding.getSeverity()) {
            case BLOCKER, MAJOR -> "error";
            case MINOR -> "warning";
            case INFO -> "note";
        };
    }
}
