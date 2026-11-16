package dev.yujian.mergeguard.service;

import dev.yujian.mergeguard.analysis.FindingDraft;
import dev.yujian.mergeguard.analysis.LlmReviewer;
import dev.yujian.mergeguard.analysis.RiskAggregator;
import dev.yujian.mergeguard.analysis.RuleEngine;
import dev.yujian.mergeguard.config.MergeGuardProperties;
import dev.yujian.mergeguard.diff.FileDiff;
import dev.yujian.mergeguard.domain.Finding;
import dev.yujian.mergeguard.domain.FindingSource;
import dev.yujian.mergeguard.domain.Review;
import dev.yujian.mergeguard.domain.Severity;
import dev.yujian.mergeguard.domain.Verdict;
import dev.yujian.mergeguard.github.GitHubClient;
import dev.yujian.mergeguard.github.GitHubException;
import dev.yujian.mergeguard.github.GhFile;
import dev.yujian.mergeguard.github.GhPullRequest;
import dev.yujian.mergeguard.repo.FindingRepository;
import dev.yujian.mergeguard.repo.ReviewRepository;
import dev.yujian.mergeguard.web.dto.Dtos;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final ReviewRepository reviewRepository;
    private final FindingRepository findingRepository;
    private final GitHubClient githubClient;
    private final RuleEngine ruleEngine;
    private final RiskAggregator riskAggregator;
    private final LlmReviewer llmReviewer;
    private final MergeGuardProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final ThreadPoolTaskExecutor reviewExecutor;
    private final MeterRegistry meterRegistry;

    public ReviewService(ReviewRepository reviewRepository, FindingRepository findingRepository,
                         GitHubClient githubClient, RuleEngine ruleEngine, RiskAggregator riskAggregator,
                         LlmReviewer llmReviewer, MergeGuardProperties properties,
                         StringRedisTemplate redisTemplate, ThreadPoolTaskExecutor reviewExecutor,
                         MeterRegistry meterRegistry) {
        this.reviewRepository = reviewRepository;
        this.findingRepository = findingRepository;
        this.githubClient = githubClient;
        this.ruleEngine = ruleEngine;
        this.riskAggregator = riskAggregator;
        this.llmReviewer = llmReviewer;
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.reviewExecutor = reviewExecutor;
        this.meterRegistry = meterRegistry;
    }

    public Dtos.ReviewDetailDto trigger(String owner, String name, int prNumber, boolean publish) {
        GhPullRequest pr = githubClient.getPull(owner, name, prNumber);
        if (pr.head() == null || pr.head().sha() == null || pr.head().sha().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "pull request has no head sha");
        }
        return execute(owner, name, prNumber, pr.head().sha(), publish, pr);
    }

    public void submit(String owner, String name, int prNumber, String headSha, boolean publish) {
        if (headSha == null || headSha.isBlank()) {
            return;
        }
        if (reviewRepository.existsByRepoOwnerAndRepoNameAndPrNumberAndHeadSha(owner, name, prNumber, headSha)) {
            return;
        }
        try {
            reviewExecutor.execute(() -> {
                try {
                    execute(owner, name, prNumber, headSha, publish, null);
                } catch (Exception e) {
                    log.warn("async review failed for {}/{}#{}: {}", owner, name, prNumber, e.getMessage());
                    meterRegistry.counter("mergeguard.review.failed").increment();
                }
            });
        } catch (Exception e) {
            log.warn("review queue rejected for {}/{}#{}: {}", owner, name, prNumber, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "review queue is full");
        }
    }

    private Dtos.ReviewDetailDto execute(String owner, String name, int prNumber, String headSha,
                                         boolean publish, GhPullRequest knownPr) {
        long started = System.currentTimeMillis();
        Optional<Review> existing = reviewRepository
                .findByRepoOwnerAndRepoNameAndPrNumberAndHeadSha(owner, name, prNumber, headSha);
        if (existing.isPresent()) {
            return toDetail(existing.get());
        }

        String lockKey = "mergeguard:lock:" + owner + "/" + name + "#" + prNumber + "@" + headSha;
        boolean locked = false;
        boolean lockUnavailable = false;
        try {
            locked = Boolean.TRUE.equals(redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, UUID.randomUUID().toString(), Duration.ofMinutes(8)));
        } catch (Exception e) {
            lockUnavailable = true;
            log.warn("redis lock unavailable, continuing without lock: {}", e.getMessage());
        }
        if (!locked && !lockUnavailable) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "review already in progress");
        }

        try {
            GhPullRequest pr = knownPr != null ? knownPr : githubClient.getPull(owner, name, prNumber);
            List<GhFile> files = githubClient.listFiles(owner, name, prNumber);
            List<FileDiff> diffs = new ArrayList<>();
            if (files != null) {
                for (GhFile file : files) {
                    diffs.add(FileDiff.parse(file));
                }
            }

            List<FindingDraft> ruleFindings = ruleEngine.analyze(diffs);
            LlmReviewer.LlmResult llm = llmReviewer.review(owner + "/" + name, pr, diffs);
            List<FindingDraft> all = new ArrayList<>(ruleFindings);
            all.addAll(llm.findings());
            RiskAggregator.Result aggregated = riskAggregator.aggregate(all, properties.getReview().getMaxFindings());

            Review review = new Review();
            review.setRepoOwner(owner);
            review.setRepoName(name);
            review.setPrNumber(prNumber);
            review.setHeadSha(headSha);
            review.setTitle(limit(pr.title(), 380));
            review.setAuthor(pr.user() == null ? null : pr.user().login());
            review.setBaseRef(pr.base() == null ? null : pr.base().ref());
            review.setPrUrl(pr.htmlUrl());
            review.setVerdict(aggregated.verdict());
            review.setScore(aggregated.score());
            review.setLlmModel(llm.model());
            review.setDurationMs(System.currentTimeMillis() - started);
            review.setInputTokens(llm.inputTokens());
            review.setOutputTokens(llm.outputTokens());
            review.setFindingsCount(aggregated.findings().size());
            review.setSummary(summary(review, llm, aggregated.findings()));
            review.setCreatedAt(Instant.now());
            review.setPublished(false);
            Review saved = reviewRepository.save(review);

            List<Finding> entities = saveFindings(saved.getId(), aggregated.findings());
            if (publish) {
                PublishOutcome outcome = publishToGitHub(saved, pr, aggregated.findings(), diffs);
                saved.setPublished(outcome.published());
                saved.setPublishError(outcome.error());
                saved = reviewRepository.save(saved);
            }

            recordMetrics(saved, aggregated.findings(), llm);
            return toDetail(saved, entities);
        } finally {
            if (locked) {
                try {
                    redisTemplate.delete(lockKey);
                } catch (Exception ignored) {
                    // TTL will clean it up
                }
            }
        }
    }

    private List<Finding> saveFindings(Long reviewId, List<FindingDraft> drafts) {
        List<Finding> entities = new ArrayList<>();
        for (FindingDraft draft : drafts) {
            Finding entity = new Finding();
            entity.setReviewId(reviewId);
            entity.setFilePath(limit(draft.filePath(), 480));
            entity.setLineNumber(draft.lineNumber());
            entity.setSeverity(draft.severity());
            entity.setCategory(limit(draft.category(), 36));
            entity.setTitle(limit(draft.title(), 280));
            entity.setEvidence(limit(draft.evidence(), 4000));
            entity.setSuggestion(limit(draft.suggestion(), 4000));
            entity.setSource(draft.source());
            entity.setConfidence(draft.confidence());
            entity.setRuleId(draft.ruleId());
            entities.add(entity);
        }
        return findingRepository.saveAll(entities);
    }

    private void recordMetrics(Review review, List<FindingDraft> findings, LlmReviewer.LlmResult llm) {
        meterRegistry.counter("mergeguard.review.completed", "verdict", review.getVerdict().name()).increment();
        Timer.builder("mergeguard.review.duration").tag("verdict", review.getVerdict().name())
                .register(meterRegistry).record(Duration.ofMillis(review.getDurationMs()));
        for (FindingDraft finding : findings) {
            Counter.builder("mergeguard.findings.created")
                    .tag("severity", finding.severity().name())
                    .tag("source", finding.source().name())
                    .register(meterRegistry).increment();
        }
        if (llm.used() && (llm.inputTokens() + llm.outputTokens()) > 0) {
            meterRegistry.counter("mergeguard.llm.tokens").increment(llm.inputTokens() + llm.outputTokens());
        }
        if (llm.error() != null) {
            meterRegistry.counter("mergeguard.llm.failures").increment();
        }
    }

    private PublishOutcome publishToGitHub(Review review, GhPullRequest pr, List<FindingDraft> findings,
                                           List<FileDiff> diffs) {
        if (!githubClient.hasToken()) {
            return new PublishOutcome(false, "github token not configured");
        }
        Map<String, Set<Integer>> added = new LinkedHashMap<>();
        for (FileDiff diff : diffs) {
            added.put(diff.filename(), new HashSet<>(diff.addedLineNumbers()));
        }
        List<GitHubClient.ReviewComment> comments = new ArrayList<>();
        for (FindingDraft finding : findings) {
            if (comments.size() >= 10) {
                break;
            }
            Integer line = finding.lineNumber();
            if (line == null || !added.getOrDefault(finding.filePath(), Set.of()).contains(line)) {
                continue;
            }
            comments.add(new GitHubClient.ReviewComment(finding.filePath(), line, "RIGHT",
                    inlineBody(finding)));
        }
        String body = summaryMarkdown(review, findings, pr);
        try {
            githubClient.postReview(review.getRepoOwner(), review.getRepoName(), review.getPrNumber(),
                    review.getHeadSha(), body, comments);
            return new PublishOutcome(true, null);
        } catch (GitHubException e) {
            log.warn("inline review failed ({}); falling back to issue comment", e.getMessage());
            try {
                githubClient.postIssueComment(review.getRepoOwner(), review.getRepoName(), review.getPrNumber(),
                        body + "\n\n> 行内评论不可用，已改为汇总评论。");
                return new PublishOutcome(true, null);
            } catch (Exception second) {
                log.warn("publishing failed: {}", second.getMessage());
                return new PublishOutcome(false, limit(second.getMessage(), 400));
            }
        }
    }

    private String inlineBody(FindingDraft finding) {
        StringBuilder builder = new StringBuilder();
        builder.append("**").append(finding.severity().name()).append(" · ").append(finding.category())
                .append("** ").append(finding.title()).append("\n\n");
        if (!finding.evidence().isBlank()) {
            builder.append("```\n").append(limit(finding.evidence(), 600)).append("\n```\n\n");
        }
        if (!finding.suggestion().isBlank()) {
            builder.append("建议：").append(finding.suggestion()).append("\n\n");
        }
        builder.append("<sub>MergeGuard");
        if (finding.ruleId() != null) {
            builder.append(" · ").append(finding.ruleId());
        }
        builder.append("</sub>");
        return builder.toString();
    }

    private String summaryMarkdown(Review review, List<FindingDraft> findings, GhPullRequest pr) {
        long blockers = findings.stream().filter(f -> f.severity() == Severity.BLOCKER).count();
        long majors = findings.stream().filter(f -> f.severity() == Severity.MAJOR).count();
        long minors = findings.stream().filter(f -> f.severity() == Severity.MINOR).count();
        StringBuilder builder = new StringBuilder();
        builder.append("## MergeGuard · ").append(review.getVerdict()).append("（")
                .append(review.getScore()).append("/100）\n\n");
        builder.append("`").append(review.getRepoOwner()).append("/").append(review.getRepoName())
                .append("#").append(review.getPrNumber()).append("`")
                .append(" · ").append(findings.size()).append(" 项发现")
                .append(" · ").append(blockers).append(" 阻断 / ").append(majors).append(" 高优 / ")
                .append(minors).append(" 提醒")
                .append(" · ").append(review.getDurationMs()).append(" ms")
                .append(" · ").append(review.getInputTokens() + review.getOutputTokens()).append(" tokens\n\n");
        if (review.getSummary() != null && !review.getSummary().isBlank()) {
            builder.append("> ").append(review.getSummary().replace("\n", " ")).append("\n\n");
        }
        if (!findings.isEmpty()) {
            builder.append("| 级别 | 文件 | 问题 |\n|---|---|---|\n");
            int rows = 0;
            for (FindingDraft finding : findings) {
                if (rows++ >= 12) break;
                builder.append("| ").append(finding.severity().name()).append(" | `")
                        .append(escape(finding.filePath())).append("` | ").append(escape(finding.title()))
                        .append(" |\n");
            }
            if (findings.size() > 12) {
                builder.append("\n其余 ").append(findings.size() - 12).append(" 项见 MergeGuard 控制台。\n");
            }
            builder.append("\n");
        }
        builder.append("<sub>规则引擎 + 模型语义审查；只做合并前风险提示，不自动合并。</sub>");
        return builder.toString();
    }

    public Dtos.ReviewDetailDto getDetail(long id) {
        Review review = reviewRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "review not found"));
        return toDetail(review);
    }

    public Dtos.PageDto<Dtos.ReviewSummaryDto> list(int page, int size, String verdict, String repo) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(50, Math.max(1, size));
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Verdict parsed = parseVerdict(verdict);
        String owner = null;
        String name = null;
        if (repo != null && !repo.isBlank()) {
            String[] parts = repo.split("/", 2);
            if (parts.length != 2) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "repo must be owner/name");
            }
            owner = parts[0];
            name = parts[1];
        }
        Page<Review> result;
        if (parsed != null && owner != null) {
            result = reviewRepository.findByVerdictAndRepoOwnerAndRepoName(parsed, owner, name, pageable);
        } else if (parsed != null) {
            result = reviewRepository.findByVerdict(parsed, pageable);
        } else if (owner != null) {
            result = reviewRepository.findByRepoOwnerAndRepoName(owner, name, pageable);
        } else {
            result = reviewRepository.findAll(pageable);
        }
        List<Dtos.ReviewSummaryDto> items = result.getContent().stream().map(this::toSummary).toList();
        return new Dtos.PageDto<>(items, result.getTotalElements(), safePage, safeSize,
                result.getTotalPages());
    }

    public Dtos.OverviewDto overview() {
        long total = reviewRepository.count();
        long blocked = reviewRepository.countByVerdict(Verdict.BLOCK);
        double blockRate = total == 0 ? 0 : (double) blocked / total;
        Double avg = reviewRepository.averageDurationMs();
        Long tokens = reviewRepository.totalTokens();
        long llmReviews = reviewRepository.countByInputTokensGreaterThan(0);

        List<Dtos.VerdictCount> verdicts = new ArrayList<>();
        for (Object[] row : reviewRepository.verdictCounts()) {
            verdicts.add(new Dtos.VerdictCount(((Verdict) row[0]).name(), ((Number) row[1]).longValue()));
        }
        List<Dtos.DailyCount> daily = dailyCounts();
        List<Dtos.ReviewSummaryDto> recent = reviewRepository.findTop10ByOrderByCreatedAtDesc()
                .stream().limit(8).map(this::toSummary).toList();
        return new Dtos.OverviewDto(total, blocked, blockRate, avg == null ? 0 : avg.longValue(),
                tokens == null ? 0 : tokens, llmReviews, verdicts, daily, recent);
    }

    private List<Dtos.DailyCount> dailyCounts() {
        Instant since = Instant.now().minus(Duration.ofDays(6));
        List<Review> reviews = reviewRepository.findByCreatedAtAfterOrderByCreatedAtAsc(since);
        Map<String, long[]> buckets = new LinkedHashMap<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM-dd");
        ZoneId zone = ZoneId.systemDefault();
        for (int i = 6; i >= 0; i--) {
            String key = Instant.now().minus(Duration.ofDays(i)).atZone(zone).toLocalDate().format(formatter);
            buckets.put(key, new long[]{0, 0});
        }
        for (Review review : reviews) {
            String key = review.getCreatedAt().atZone(zone).toLocalDate().format(formatter);
            long[] bucket = buckets.get(key);
            if (bucket != null) {
                bucket[0]++;
                if (review.getVerdict() == Verdict.BLOCK) bucket[1]++;
            }
        }
        List<Dtos.DailyCount> daily = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : buckets.entrySet()) {
            daily.add(new Dtos.DailyCount(entry.getKey(), entry.getValue()[0], entry.getValue()[1]));
        }
        return daily;
    }

    private Verdict parseVerdict(String verdict) {
        if (verdict == null || verdict.isBlank()) {
            return null;
        }
        try {
            return Verdict.valueOf(verdict.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown verdict");
        }
    }

    private String summary(Review review, LlmReviewer.LlmResult llm, List<FindingDraft> findings) {
        if (llm.used() && llm.summary() != null && !llm.summary().isBlank()) {
            return limit(llm.summary(), 280);
        }
        long blockers = findings.stream().filter(f -> f.severity() == Severity.BLOCKER).count();
        long majors = findings.stream().filter(f -> f.severity() == Severity.MAJOR).count();
        if (findings.isEmpty()) {
            return "未发现阻断或高优风险，可以合并；仍建议人工确认业务语义。";
        }
        return "共 " + findings.size() + " 项发现，" + blockers + " 个阻断、" + majors
                + " 个高优；" + (review.getVerdict() == Verdict.BLOCK ? "不建议合并。" : "建议修复后再合并。");
    }

    private Dtos.ReviewSummaryDto toSummary(Review review) {
        return new Dtos.ReviewSummaryDto(review.getId(), review.repoFullName(), review.getPrNumber(),
                review.getTitle(), review.getVerdict().name(), review.getScore(), review.getFindingsCount(),
                review.getDurationMs(), review.getInputTokens() + review.getOutputTokens(),
                review.isPublished(), review.getCreatedAt(), review.getPrUrl());
    }

    private Dtos.ReviewDetailDto toDetail(Review review) {
        return toDetail(review, findingRepository.findByReviewId(review.getId()));
    }

    private Dtos.ReviewDetailDto toDetail(Review review, List<Finding> entities) {
        entities.sort(Comparator.comparingInt((Finding f) -> f.getSeverity().ordinal())
                .thenComparing(f -> f.getLineNumber() == null ? Integer.MAX_VALUE : f.getLineNumber()));
        List<Dtos.FindingDto> findings = entities.stream()
                .map(f -> new Dtos.FindingDto(f.getId(), f.getFilePath(), f.getLineNumber(),
                        f.getSeverity().name(), f.getCategory(), f.getTitle(), f.getEvidence(),
                        f.getSuggestion(), f.getSource().name(), f.getConfidence(), f.getRuleId()))
                .toList();
        return new Dtos.ReviewDetailDto(review.getId(), review.repoFullName(), review.getPrNumber(),
                review.getTitle(), review.getAuthor(), review.getBaseRef(), review.getHeadSha(),
                review.getVerdict().name(), review.getScore(), review.getSummary(), review.getLlmModel(),
                review.getDurationMs(), review.getInputTokens(), review.getOutputTokens(),
                review.isPublished(), review.getPublishError(), review.getCreatedAt(), review.getPrUrl(), findings);
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    private String limit(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record PublishOutcome(boolean published, String error) {
    }
}
