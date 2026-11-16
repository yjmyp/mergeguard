package dev.yujian.mergeguard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "mg_repository", uniqueConstraints = @UniqueConstraint(name = "uk_mg_repo_name", columnNames = {"owner", "name"}))
public class RepoConfig {

    @Id
    private Long id;

    @Column(nullable = false, length = 120)
    private String owner;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(name = "full_name", nullable = false, length = 280)
    private String fullName;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "auto_review", nullable = false)
    private boolean autoReview = true;

    @Column(name = "ignored_paths", length = 2000)
    private String ignoredPaths = "";

    @Column(name = "disabled_rules", length = 1000)
    private String disabledRules = "";

    @Column(name = "min_publish_severity", length = 16)
    private String minPublishSeverity = "MINOR";

    @Column(name = "max_inline_comments")
    private Integer maxInlineComments = 10;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isAutoReview() { return autoReview; }
    public void setAutoReview(boolean autoReview) { this.autoReview = autoReview; }
    public String getIgnoredPaths() { return ignoredPaths; }
    public void setIgnoredPaths(String ignoredPaths) { this.ignoredPaths = ignoredPaths; }
    public String getDisabledRules() { return disabledRules; }
    public void setDisabledRules(String disabledRules) { this.disabledRules = disabledRules; }
    public String getMinPublishSeverity() { return minPublishSeverity; }
    public void setMinPublishSeverity(String minPublishSeverity) { this.minPublishSeverity = minPublishSeverity; }
    public Integer getMaxInlineComments() { return maxInlineComments; }
    public void setMaxInlineComments(Integer maxInlineComments) { this.maxInlineComments = maxInlineComments; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
