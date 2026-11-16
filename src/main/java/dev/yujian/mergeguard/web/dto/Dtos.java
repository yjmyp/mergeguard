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
                             String evidence, String suggestion, String source, double confidence, String ruleId) {
    }

    public record ReviewDetailDto(Long id, String repo, int prNumber, String title, String author, String baseRef,
                                  String headSha, String verdict, int score, String summary, String llmModel,
                                  long durationMs, int inputTokens, int outputTokens, boolean published,
                                  String publishError, Instant createdAt, String prUrl, List<FindingDto> findings) {
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

    public record RepoUpdateRequest(Boolean enabled, Boolean autoReview) {
    }

    public record RepoDto(Long id, String owner, String name, String fullName, boolean enabled, boolean autoReview,
                          Instant updatedAt) {
    }

    public record ScanResult(int queued, int openPulls) {
    }
}
