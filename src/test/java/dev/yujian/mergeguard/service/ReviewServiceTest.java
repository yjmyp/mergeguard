package dev.yujian.mergeguard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import dev.yujian.mergeguard.analysis.LlmReviewer;
import dev.yujian.mergeguard.analysis.RiskAggregator;
import dev.yujian.mergeguard.analysis.RuleEngine;
import dev.yujian.mergeguard.config.MergeGuardProperties;
import dev.yujian.mergeguard.domain.Finding;
import dev.yujian.mergeguard.domain.FindingSource;
import dev.yujian.mergeguard.domain.Review;
import dev.yujian.mergeguard.domain.ReviewFile;
import dev.yujian.mergeguard.domain.Severity;
import dev.yujian.mergeguard.domain.Verdict;
import dev.yujian.mergeguard.github.GitHubClient;
import dev.yujian.mergeguard.repo.FindingRepository;
import dev.yujian.mergeguard.repo.RepoConfigRepository;
import dev.yujian.mergeguard.repo.ReviewFileRepository;
import dev.yujian.mergeguard.repo.ReviewRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @Mock private ReviewRepository reviewRepository;
    @Mock private FindingRepository findingRepository;
    @Mock private ReviewFileRepository reviewFileRepository;
    @Mock private RepoConfigRepository repoConfigRepository;
    @Mock private GitHubClient githubClient;
    @Mock private StringRedisTemplate redisTemplate;

    private ReviewService service;

    @BeforeEach
    void setUp() {
        service = new ReviewService(reviewRepository, findingRepository, reviewFileRepository,
                repoConfigRepository, githubClient, new RuleEngine(), new RiskAggregator(),
                new LlmReviewer(null, new MergeGuardProperties(), new com.fasterxml.jackson.databind.ObjectMapper()),
                new MergeGuardProperties(), redisTemplate, new SimpleMeterRegistry());
    }

    @Test
    void listsReviewsWithMappedFields() {
        Review review = review(1L);
        when(reviewRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(review),
                PageRequest.of(0, 10), 1));
        var page = service.list(0, 10, null, null);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items().get(0).verdict()).isEqualTo("BLOCK");
    }

    @Test
    void detailIncludesFindingsAndStageTimings() {
        Review review = review(1L);
        when(reviewRepository.findById(1L)).thenReturn(Optional.of(review));
        Finding finding = new Finding();
        finding.setId(11L);
        finding.setReviewId(1L);
        finding.setFilePath("A.java");
        finding.setLineNumber(4);
        finding.setSeverity(Severity.MAJOR);
        finding.setCategory("SECURITY");
        finding.setTitle("issue");
        finding.setSource(FindingSource.RULE);
        finding.setConfidence(0.9);
        when(findingRepository.findByReviewId(1L)).thenReturn(List.of(finding));
        var detail = service.getDetail(1L);
        assertThat(detail.findings()).hasSize(1);
        assertThat(detail.findings().get(0).status()).isEqualTo("OPEN");
        assertThat(detail.llmCoverage()).isEqualTo(100);
    }

    @Test
    void recordsFeedbackAndComputesQuality() {
        Finding finding = new Finding();
        finding.setId(3L);
        finding.setStatus(dev.yujian.mergeguard.domain.FindingStatus.OPEN);
        when(findingRepository.findById(3L)).thenReturn(Optional.of(finding));
        when(findingRepository.save(any())).thenReturn(finding);
        service.feedback(3L, "dismissed", "false positive");
        assertThat(finding.getStatus()).isEqualTo(dev.yujian.mergeguard.domain.FindingStatus.DISMISSED);

        when(findingRepository.ruleQuality()).thenReturn(List.<Object[]>of(
                new Object[]{"R-X", 10L, 5L, 2L, 1L, 2L, 0.9}));
        var quality = service.quality();
        assertThat(quality.totalFindings()).isEqualTo(10);
        assertThat(quality.acceptanceRate()).isEqualTo(0.75);
        assertThat(quality.rules()).hasSize(1);
    }

    @Test
    void returnsStoredDiffSnapshot() {
        when(reviewRepository.findById(2L)).thenReturn(Optional.of(review(2L)));
        ReviewFile file = new ReviewFile();
        file.setReviewId(2L);
        file.setFilePath("A.java");
        file.setPatch("@@ -1 +1 @@\n+x");
        when(reviewFileRepository.findByReviewIdOrderByFilePathAsc(2L)).thenReturn(List.of(file));
        var diff = service.diff(2L);
        assertThat(diff.files()).hasSize(1);
        assertThat(diff.files().get(0).patch()).contains("+x");
    }

    private Review review(long id) {
        Review review = new Review();
        review.setId(id);
        review.setRepoOwner("o");
        review.setRepoName("r");
        review.setPrNumber(3);
        review.setHeadSha("sha");
        review.setTitle("title");
        review.setVerdict(Verdict.BLOCK);
        review.setScore(0);
        review.setFindingsCount(1);
        review.setDurationMs(100);
        review.setCreatedAt(Instant.now());
        return review;
    }
}
