package dev.yujian.mergeguard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "mg_review_file", indexes = @Index(name = "idx_mg_review_file_review", columnList = "review_id"))
public class ReviewFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "review_id", nullable = false)
    private Long reviewId;

    @Column(name = "file_path", nullable = false, length = 500)
    private String filePath;

    @Column(nullable = false)
    private int additions;

    @Column(nullable = false)
    private int deletions;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String patch;

    @Column(name = "patch_hash", length = 64)
    private String patchHash;

    public Long getId() { return id; }
    public Long getReviewId() { return reviewId; }
    public void setReviewId(Long reviewId) { this.reviewId = reviewId; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public int getAdditions() { return additions; }
    public void setAdditions(int additions) { this.additions = additions; }
    public int getDeletions() { return deletions; }
    public void setDeletions(int deletions) { this.deletions = deletions; }
    public String getPatch() { return patch; }
    public void setPatch(String patch) { this.patch = patch; }
    public String getPatchHash() { return patchHash; }
    public void setPatchHash(String patchHash) { this.patchHash = patchHash; }
}
