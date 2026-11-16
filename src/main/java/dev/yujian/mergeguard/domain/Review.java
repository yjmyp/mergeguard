package dev.yujian.mergeguard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "mg_review",
        uniqueConstraints = @UniqueConstraint(name = "uk_mg_review_head",
                columnNames = {"repo_owner", "repo_name", "pr_number", "head_sha"}),
        indexes = {
                @Index(name = "idx_mg_review_created", columnList = "created_at"),
                @Index(name = "idx_mg_review_verdict", columnList = "verdict")
        })
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repo_owner", nullable = false, length = 120)
    private String repoOwner;

    @Column(name = "repo_name", nullable = false, length = 160)
    private String repoName;

    @Column(name = "pr_number", nullable = false)
    private int prNumber;

    @Column(name = "head_sha", nullable = false, length = 64)
    private String headSha;

    @Column(nullable = false, length = 400)
    private String title;

    @Column(length = 120)
    private String author;

    @Column(name = "base_ref", length = 120)
    private String baseRef;

    @Column(name = "pr_url", length = 500)
    private String prUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Verdict verdict;

    @Column(nullable = false)
    private int score;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "llm_model", length = 80)
    private String llmModel;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    @Column(name = "findings_count", nullable = false)
    private int findingsCount;

    @Column(nullable = false)
    private boolean published;

    @Column(name = "publish_error", columnDefinition = "TEXT")
    private String publishError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public String getRepoOwner() { return repoOwner; }
    public void setRepoOwner(String repoOwner) { this.repoOwner = repoOwner; }
    public String getRepoName() { return repoName; }
    public void setRepoName(String repoName) { this.repoName = repoName; }
    public int getPrNumber() { return prNumber; }
    public void setPrNumber(int prNumber) { this.prNumber = prNumber; }
    public String getHeadSha() { return headSha; }
    public void setHeadSha(String headSha) { this.headSha = headSha; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }
    public String getBaseRef() { return baseRef; }
    public void setBaseRef(String baseRef) { this.baseRef = baseRef; }
    public String getPrUrl() { return prUrl; }
    public void setPrUrl(String prUrl) { this.prUrl = prUrl; }
    public Verdict getVerdict() { return verdict; }
    public void setVerdict(Verdict verdict) { this.verdict = verdict; }
    public int getScore() { return score; }
    public void setScore(int score) { this.score = score; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getLlmModel() { return llmModel; }
    public void setLlmModel(String llmModel) { this.llmModel = llmModel; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public int getInputTokens() { return inputTokens; }
    public void setInputTokens(int inputTokens) { this.inputTokens = inputTokens; }
    public int getOutputTokens() { return outputTokens; }
    public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }
    public int getFindingsCount() { return findingsCount; }
    public void setFindingsCount(int findingsCount) { this.findingsCount = findingsCount; }
    public boolean isPublished() { return published; }
    public void setPublished(boolean published) { this.published = published; }
    public String getPublishError() { return publishError; }
    public void setPublishError(String publishError) { this.publishError = publishError; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String repoFullName() { return repoOwner + "/" + repoName; }
}
