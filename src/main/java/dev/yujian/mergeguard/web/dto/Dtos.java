package dev.yujian.mergeguard.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.List;

public final class Dtos {

    private Dtos() {
    }

    public record ReviewSummaryDto(Long id, String repo, int prNumber, String title, String verdict, int score,
                                   int findings, long durationMs, int tokens, boolean published,
                                   Instant createdAt, String prUrl) {
    }

    public record FindingDto(Long id, String file, Integer line, String severity, String category, String title,
                             String evidence, String suggestion, String source, double confidence, String ruleId,
                             String status, String feedbackNote, Instant feedbackAt) {
    }

    public record ReviewDetailDto(Long id, String repo, int prNumber, String title, String author, String baseRef,
                                  String headSha, String verdict, int score, String summary, String llmModel,
                                  long durationMs, int inputTokens, int outputTokens, boolean published,
                                  String publishError, Instant createdAt, String prUrl, List<FindingDto> findings,
                                  long diffMs, long ruleMs, long llmMs, long publishMs, int llmChunks,
                                  int llmCoverage, int injectionNeutralized) {
    }

    public record VerdictCount(String verdict, long count) {
    }

    public record DailyCount(String date, long total, long blocked) {
    }

    public record OverviewDto(long reviews, long blocked, double blockRate, long avgDurationMs, long totalTokens,
                              long llmReviews, List<VerdictCount> verdicts, List<DailyCount> daily,
                              List<ReviewSummaryDto> recent) {
    }

    public record PageDto<T>(List<T> items, long total, int page, int size, int pages) {
    }

    public record TriggerRequest(@NotBlank String owner, @NotBlank String name, @Positive int prNumber,
                                 boolean publish) {
    }

    public record RepoRequest(@NotBlank String owner, @NotBlank String name, Boolean autoReview) {
    }

    public record RepoUpdateRequest(Boolean enabled, Boolean autoReview, String ignoredPaths, String disabledRules,
                                    String minPublishSeverity, Integer maxInlineComments) {
    }

    public record RepoDto(Long id, String owner, String name, String fullName, boolean enabled, boolean autoReview,
                          String ignoredPaths, String disabledRules, String minPublishSeverity,
                          Integer maxInlineComments, Instant updatedAt) {
    }

    public record ScanResult(int queued, int openPulls) {
    }

    public record JobDto(Long id, String owner, String name, int prNumber, String headSha, boolean publish,
                         String status, int attempts, int maxAttempts, Instant nextAttemptAt, String lastError,
                         Long reviewId, Instant createdAt, Instant finishedAt) {
    }

    public record JobStatsDto(long queued, long running, long done, long failed) {
    }

    public record FeedbackRequest(@NotBlank String status, String note) {
    }

    public record QualityRuleDto(String ruleId, long total, long accepted, long dismissed, long fixed, long open,
                                 double acceptanceRate, double avgConfidence) {
    }

    public record QualityDto(long totalFindings, long judged, long accepted, long dismissed, long fixed,
                             double acceptanceRate, List<QualityRuleDto> rules) {
    }

    public record DiffFileDto(String file, int additions, int deletions, String patch) {
    }

    public record DiffDto(Long reviewId, String headSha, List<DiffFileDto> files) {
    }
}
