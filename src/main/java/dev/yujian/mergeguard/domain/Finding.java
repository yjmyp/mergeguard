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
import java.time.Instant;

@Entity
@Table(name = "mg_finding", indexes = @Index(name = "idx_mg_finding_review", columnList = "review_id"))
public class Finding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "review_id", nullable = false)
    private Long reviewId;

    @Column(name = "file_path", nullable = false, length = 500)
    private String filePath;

    @Column(name = "line_number")
    private Integer lineNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Severity severity;

    @Column(nullable = false, length = 40)
    private String category;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String evidence;

    @Column(columnDefinition = "TEXT")
    private String suggestion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FindingSource source;

    @Column(nullable = false)
    private double confidence;

    @Column(name = "rule_id", length = 24)
    private String ruleId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = true, length = 16)
    private FindingStatus status = FindingStatus.OPEN;

    @Column(name = "feedback_note", length = 1000)
    private String feedbackNote;

    @Column(name = "feedback_at")
    private Instant feedbackAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getReviewId() { return reviewId; }
    public void setReviewId(Long reviewId) { this.reviewId = reviewId; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public Integer getLineNumber() { return lineNumber; }
    public void setLineNumber(Integer lineNumber) { this.lineNumber = lineNumber; }
    public Severity getSeverity() { return severity; }
    public void setSeverity(Severity severity) { this.severity = severity; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getEvidence() { return evidence; }
    public void setEvidence(String evidence) { this.evidence = evidence; }
    public String getSuggestion() { return suggestion; }
    public void setSuggestion(String suggestion) { this.suggestion = suggestion; }
    public FindingSource getSource() { return source; }
    public void setSource(FindingSource source) { this.source = source; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }
    public FindingStatus getStatus() { return status == null ? FindingStatus.OPEN : status; }
    public void setStatus(FindingStatus status) { this.status = status; }
    public String getFeedbackNote() { return feedbackNote; }
    public void setFeedbackNote(String feedbackNote) { this.feedbackNote = feedbackNote; }
    public Instant getFeedbackAt() { return feedbackAt; }
    public void setFeedbackAt(Instant feedbackAt) { this.feedbackAt = feedbackAt; }
}
