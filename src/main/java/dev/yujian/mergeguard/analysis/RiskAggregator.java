package dev.yujian.mergeguard.analysis;

import dev.yujian.mergeguard.domain.FindingSource;
import dev.yujian.mergeguard.domain.Severity;
import dev.yujian.mergeguard.domain.Verdict;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RiskAggregator {

    public Result aggregate(List<FindingDraft> drafts, int maxFindings) {
        List<FindingDraft> deduped = dedupe(drafts);
        deduped.sort(Comparator
                .comparingInt((FindingDraft f) -> f.severity().ordinal())
                .thenComparing(Comparator.comparingDouble(FindingDraft::confidence).reversed()));
        if (deduped.size() > maxFindings) {
            deduped = new ArrayList<>(deduped.subList(0, maxFindings));
        }
        int score = 100;
        boolean hasBlocker = false;
        boolean hasMajor = false;
        for (FindingDraft finding : deduped) {
            score -= finding.severity().penalty();
            if (finding.severity() == Severity.BLOCKER) hasBlocker = true;
            if (finding.severity() == Severity.MAJOR) hasMajor = true;
        }
        score = Math.max(0, score);
        Verdict verdict;
        if (hasBlocker) {
            verdict = Verdict.BLOCK;
        } else if (hasMajor || score < 75) {
            verdict = Verdict.FIX;
        } else {
            verdict = Verdict.SHIP;
        }
        return new Result(deduped, verdict, score);
    }

    private List<FindingDraft> dedupe(List<FindingDraft> drafts) {
        List<FindingDraft> collapsed = collapseSameRule(drafts);
        Map<String, FindingDraft> byKey = new HashMap<>();
        for (FindingDraft draft : collapsed) {
            String key = draft.filePath() + "|" + (draft.lineNumber() == null ? "-" : draft.lineNumber())
                    + "|" + draft.category();
            FindingDraft existing = byKey.get(key);
            if (existing == null || draft.severity().ordinal() < existing.severity().ordinal()
                    || (draft.source() == FindingSource.RULE && existing.source() == FindingSource.LLM)) {
                byKey.put(key, draft);
            }
        }
        List<FindingDraft> rules = byKey.values().stream()
                .filter(f -> f.source() == FindingSource.RULE).toList();
        List<FindingDraft> result = new ArrayList<>();
        for (FindingDraft finding : byKey.values()) {
            if (finding.source() == FindingSource.LLM && nearRule(finding, rules)) {
                continue;
            }
            result.add(finding);
        }
        return result;
    }

    private List<FindingDraft> collapseSameRule(List<FindingDraft> drafts) {
        Map<String, FindingDraft> map = new HashMap<>();
        for (FindingDraft draft : drafts) {
            if (draft.source() != FindingSource.RULE || draft.ruleId() == null) {
                map.put("llm-" + map.size(), draft);
                continue;
            }
            String key = draft.ruleId() + "|" + draft.filePath();
            FindingDraft existing = map.get(key);
            if (existing == null) {
                map.put(key, draft);
            } else {
                String evidence = existing.evidence();
                if (draft.evidence() != null && !draft.evidence().isBlank()
                        && !evidence.contains(draft.evidence())) {
                    evidence = evidence + "\n" + draft.evidence();
                }
                map.put(key, new FindingDraft(existing.filePath(), existing.lineNumber(), existing.severity(),
                        existing.category(), existing.title(), evidence, existing.suggestion(),
                        existing.source(), existing.ruleId(), existing.confidence()));
            }
        }
        return new ArrayList<>(map.values());
    }

    private boolean nearRule(FindingDraft llm, List<FindingDraft> rules) {
        for (FindingDraft rule : rules) {
            if (rule.filePath().equals(llm.filePath()) && rule.lineNumber() != null && llm.lineNumber() != null
                    && Math.abs(rule.lineNumber() - llm.lineNumber()) <= 2) {
                return true;
            }
        }
        return false;
    }

    public record Result(List<FindingDraft> findings, Verdict verdict, int score) {
    }
}
