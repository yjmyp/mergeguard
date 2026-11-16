package dev.yujian.mergeguard.analysis;

import dev.yujian.mergeguard.domain.FindingSource;
import dev.yujian.mergeguard.domain.Severity;

public record FindingDraft(
        String filePath,
        Integer lineNumber,
        Severity severity,
        String category,
        String title,
        String evidence,
        String suggestion,
        FindingSource source,
        String ruleId,
        double confidence
) {
    public static FindingDraft rule(String filePath, Integer line, Severity severity, String category,
                                    String ruleId, String title, String evidence, String suggestion) {
        return new FindingDraft(filePath, line, severity, category, title, evidence, suggestion,
                FindingSource.RULE, ruleId, 1.0);
    }

    public static FindingDraft llm(String filePath, Integer line, Severity severity, String category,
                                   String title, String evidence, String suggestion, double confidence) {
        return new FindingDraft(filePath, line, severity, category, title, evidence, suggestion,
                FindingSource.LLM, null, confidence);
    }
}
