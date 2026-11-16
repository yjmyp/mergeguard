package dev.yujian.mergeguard.service;

import dev.yujian.mergeguard.analysis.FindingDraft;
import dev.yujian.mergeguard.analysis.LlmReviewer;
import dev.yujian.mergeguard.analysis.RiskAggregator;
import dev.yujian.mergeguard.analysis.RuleEngine;
import dev.yujian.mergeguard.config.MergeGuardProperties;
import dev.yujian.mergeguard.diff.FileDiff;
import dev.yujian.mergeguard.domain.Finding;
import dev.yujian.mergeguard.domain.FindingStatus;
import dev.yujian.mergeguard.domain.RepoConfig;
import dev.yujian.mergeguard.domain.Review;
import dev.yujian.mergeguard.domain.ReviewFile;
import dev.yujian.mergeguard.domain.ReviewJob;
import dev.yujian.mergeguard.domain.Severity;
import dev.yujian.mergeguard.domain.Verdict;
import dev.yujian.mergeguard.github.GitHubClient;
import dev.yujian.mergeguard.github.GitHubException;
import dev.yujian.mergeguard.github.GhFile;
import dev.yujian.mergeguard.github.GhPullRequest;
import dev.yujian.mergeguard.repo.FindingRepository;
import dev.yujian.mergeguard.repo.RepoConfigRepository;
import dev.yujian.mergeguard.repo.ReviewFileRepository;
import dev.yujian.mergeguard.repo.ReviewRepository;
import dev.yujian.mergeguard.web.dto.Dtos;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
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
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);
    public static final String SUMMARY_MARKER = "<!-- mergeguard:summary -->";
    private static final int MAX_PATCH_CHARS = 200_000;

    private final ReviewRepository reviewRepository;
    private final FindingRepository findingRepository;
    private final ReviewFileRepository reviewFileRepository;
    private final RepoConfigRepository repoConfigRepository;
    private final GitHubClient githubClient;
    private final RuleEngine ruleEngine;
    private final RiskAggregator riskAggregator;
    private final LlmReviewer llmReviewer;
    private final MergeGuardProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    public ReviewService(ReviewRepository reviewRepository, FindingRepository findingRepository,
                         ReviewFileRepository reviewFileRepository, RepoConfigRepository repoConfigRepository,
                         GitHubClient githubClient, RuleEngine ruleEngine, RiskAggregator riskAggregator,
                         LlmReviewer llmReviewer, MergeGuardProperties properties,
                         StringRedisTemplate redisTemplate, MeterRegistry meterRegistry) {
        this.reviewRepository = reviewRepository;
        this.findingRepository = findingRepository;
        this.reviewFileRepository = reviewFileRepository;
        this.repoConfigRepository = repoConfigRepository;
        this.githubClient = githubClient;
        this.ruleEngine = ruleEngine;
        this.riskAggregator = riskAggregator;
        this.llmReviewer = llmReviewer;
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
    }

    public Dtos.ReviewDetailDto trigger(String owner, String name, int prNumber, boolean publish) {
        GhPullRequest pr = githubClient.getPull(owner, name, prNumber);
        if (pr.head() == null || pr.head().sha() == null || pr.head().sha().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "pull request has no head sha");
        }
        return execute(owner, name, prNumber, pr.head().sha(), publish, pr);
    }

    public Dtos.ReviewDetailDto executeForJob(ReviewJob job) {
        return execute(job.getRepoOwner(), job.getRepoName(), job.getPrNumber(), job.getHeadSha(),
                job.isPublish(), null);
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
            long diffStarted = System.currentTimeMillis();
            List<GhFile> files = githubClient.listFiles(owner, name, prNumber);
            List<FileDiff> diffs = new ArrayList<>();
            if (files != null) {
                for (GhFile file : files) {
                    diffs.add(FileDiff.parse(file));
                }
            }
            long diffMs = System.currentTimeMillis() - diffStarted;

            long ruleStarted = System.currentTimeMillis();
            List<FindingDraft> ruleFindings = ruleEngine.analyze(diffs);
            long ruleMs = System.currentTimeMillis() - ruleStarted;

            LlmReviewer.LlmResult llm = llmReviewer.review(owner + "/" + name, pr, diffs);
            List<FindingDraft> all = applyPolicy(owner, name, new ArrayList<>(concat(ruleFindings, llm.findings())));
            RiskAggregator.Result aggregated = riskAggregator.aggregate(all, properties.getReview().getMaxFindings());

            List<Review> previous = reviewRepository
                    .findByRepoOwnerAndRepoNameAndPrNumberOrderByCreatedAtDesc(owner, name, prNumber);
            Long previousId = previous.isEmpty() ? null : previous.get(0).getId();

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
            review.setDiffMs(diffMs);
            review.setRuleMs(ruleMs);
            review.setLlmMs(llm.latencyMs());
            review.setLlmChunks(llm.chunks());
            review.setLlmCoverage(llm.coveragePercent());
            review.setInjectionNeutralized(llm.neutralized());
            review.setInputTokens(llm.inputTokens());
            review.setOutputTokens(llm.outputTokens());
            review.setFindingsCount(aggregated.findings().size());
            review.setSummary(summary(review, llm, aggregated.findings()));
            review.setCreatedAt(Instant.now());
            review.setPublished(false);
            Review saved = reviewRepository.save(review);

            List<Finding> entities = saveFindings(saved.getId(), aggregated.findings());
            saveFiles(saved.getId(), diffs);

            if (publish) {
                long publishStarted = System.currentTimeMillis();
                RepoConfig config = repoConfigRepository.findByOwnerAndName(owner, name).orElse(null);
                PublishOutcome outcome = publishToGitHub(saved, pr, aggregated.findings(), diffs, config, previousId);
                saved.setPublished(outcome.published());
                saved.setPublishError(outcome.error());
                saved.setSummaryCommentId(outcome.summaryCommentId());
                saved.setCheckRunId(outcome.checkRunId());
                saved.setPublishMs(System.currentTimeMillis() - publishStarted);
                saved.setDurationMs(System.currentTimeMillis() - started);
                saved = reviewRepository.save(saved);
            }

            recordMetrics(saved, aggregated.findings(), llm);
            return toDetail(saved, entities);
        } finally {
            if (locked) {
                try {
                    redisTemplate.delete(lockKey);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private List<FindingDraft> concat(List<FindingDraft> left, List<FindingDraft> right) {
        List<FindingDraft> all = new ArrayList<>(left.size() + right.size());
        all.addAll(left);
        all.addAll(right);
        return all;
    }

    private List<FindingDraft> applyPolicy(String owner, String name, List<FindingDraft> findings) {
        Optional<RepoConfig> config = repoConfigRepository.findByOwnerAndName(owner, name);
        if (config.isEmpty()) {
            return findings;
        }
        RepoConfig repo = config.get();
        List<String> ignored = splitPatterns(repo.getIgnoredPaths());
        Set<String> disabled = new HashSet<>(splitPatterns(repo.getDisabledRules()));
        List<FindingDraft> result = new ArrayList<>();
        for (FindingDraft finding : findings) {
            if (finding.ruleId() != null && disabled.contains(finding.ruleId())) {
                continue;
            }
            boolean skip = false;
            for (String pattern : ignored) {
                if (pathMatches(pattern, finding.filePath())) {
                    skip = true;
                    break;
                }
            }
            if (!skip) {
                result.add(finding);
            }
        }
        return result;
    }

    private List<String> splitPatterns(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> patterns = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                patterns.add(trimmed);
            }
        }
        return patterns;
    }

    private boolean pathMatches(String pattern, String path) {
        if (path == null) {
            return false;
        }
        if (pattern.contains("*")) {
            String regex = pattern.replace(".", "\\.").replace("*", ".*");
            return path.matches(regex);
        }
        return path.equals(pattern) || path.startsWith(pattern.endsWith("/") ? pattern : pattern + "/")
                || path.contains(pattern);
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
            entity.setStatus(FindingStatus.OPEN);
            entities.add(entity);
        }
        return findingRepository.saveAll(entities);
    }

    private void saveFiles(Long reviewId, List<FileDiff> diffs) {
        List<ReviewFile> files = new ArrayList<>();
        for (FileDiff diff : diffs) {
            ReviewFile file = new ReviewFile();
            file.setReviewId(reviewId);
            file.setFilePath(limit(diff.filename(), 480));
            file.setAdditions(diff.file().additions());
            file.setDeletions(diff.file().deletions());
            String patch = diff.file().patch();
            if (patch != null && patch.length() > MAX_PATCH_CHARS) {
                patch = patch.substring(0, MAX_PATCH_CHARS) + "\n... patch truncated\n";
            }
            file.setPatch(patch);
            file.setPatchHash(patch == null ? null : sha256(patch));
            files.add(file);
        }
        reviewFileRepository.saveAll(files);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
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
        if (llm.neutralized() > 0) {
            meterRegistry.counter("mergeguard.llm.injection_neutralized").increment(llm.neutralized());
        }
        meterRegistry.counter("mergeguard.llm.chunks").increment(llm.chunks());
    }

    private PublishOutcome publishToGitHub(Review review, GhPullRequest pr, List<FindingDraft> findings,
                                           List<FileDiff> diffs, RepoConfig config, Long previousReviewId) {
        if (!githubClient.hasToken()) {
            return new PublishOutcome(false, "github token not configured", null, null);
        }
        Map<String, Set<Integer>> added = new LinkedHashMap<>();
        for (FileDiff diff : diffs) {
            added.put(diff.filename(), new HashSet<>(diff.addedLineNumbers()));
        }
        Severity minimum = Severity.from(config == null ? null : config.getMinPublishSeverity(), Severity.MINOR);
        int maxInline = config == null || config.getMaxInlineComments() == null
                ? 10 : Math.max(0, Math.min(30, config.getMaxInlineComments()));
        List<Finding> previousFindings = previousReviewId == null ? List.of()
                : findingRepository.findByReviewId(previousReviewId);

        List<GitHubClient.ReviewComment> comments = new ArrayList<>();
        for (FindingDraft finding : findings) {
            if (comments.size() >= maxInline) {
                break;
            }
            if (finding.severity().ordinal() > minimum.ordinal()) {
                continue;
            }
            Integer line = finding.lineNumber();
            if (line == null || !added.getOrDefault(finding.filePath(), Set.of()).contains(line)) {
                continue;
            }
            if (alreadyReported(finding, previousFindings)) {
                continue;
            }
            comments.add(new GitHubClient.ReviewComment(finding.filePath(), line, "RIGHT",
                    inlineBody(finding)));
        }

        String body = summaryMarkdown(review, findings, pr);
        Long summaryCommentId = null;
        boolean published = false;
        String error = null;
        try {
            summaryCommentId = upsertSummaryComment(review, body);
            published = true;
        } catch (Exception e) {
            log.warn("summary comment failed: {}", e.getMessage());
            error = limit(e.getMessage(), 400);
        }
        if (published && !comments.isEmpty()) {
            try {
                githubClient.postReview(review.getRepoOwner(), review.getRepoName(), review.getPrNumber(),
                        review.getHeadSha(), body, comments);
            } catch (GitHubException e) {
                log.warn("inline review failed ({}); summary comment remains", e.getMessage());
            }
        }
        Long checkRunId = publishCheckRun(review, findings, added);
        if (checkRunId != null) {
            published = true;
        }
        return new PublishOutcome(published, error, summaryCommentId, checkRunId);
    }

    private Long upsertSummaryComment(Review review, String body) {
        String payload = SUMMARY_MARKER + "\n" + body;
        for (GitHubClient.GhComment comment : githubClient.listIssueComments(review.getRepoOwner(),
                review.getRepoName(), review.getPrNumber())) {
            if (comment.body() != null && comment.body().contains(SUMMARY_MARKER)) {
                return githubClient.updateIssueComment(review.getRepoOwner(), review.getRepoName(),
                        comment.id(), payload);
            }
        }
        return githubClient.postIssueComment(review.getRepoOwner(), review.getRepoName(),
                review.getPrNumber(), payload);
    }

    private Long publishCheckRun(Review review, List<FindingDraft> findings, Map<String, Set<Integer>> added) {
        String conclusion = switch (review.getVerdict()) {
            case BLOCK -> "failure";
            case FIX -> "neutral";
            case SHIP -> "success";
        };
        List<GitHubClient.CheckAnnotation> annotations = new ArrayList<>();
        for (FindingDraft finding : findings) {
            if (annotations.size() >= 20) {
                break;
            }
            Integer line = finding.lineNumber();
            if (line == null || !added.getOrDefault(finding.filePath(), Set.of()).contains(line)) {
                continue;
            }
            String level = switch (finding.severity()) {
                case BLOCKER -> "failure";
                case MAJOR -> "warning";
                default -> "notice";
            };
            annotations.add(new GitHubClient.CheckAnnotation(finding.filePath(), line, level,
                    limit(finding.title(), 60),
                    limit(finding.suggestion(), 200)));
        }
        try {
            return githubClient.createCheckRun(review.getRepoOwner(), review.getRepoName(), review.getHeadSha(),
                    conclusion, "MergeGuard " + review.getVerdict().name() + " · " + review.getScore() + "/100",
                    limit(review.getSummary(), 400), annotations, "mergeguard:" + review.getId());
        } catch (Exception e) {
            log.info("check run unavailable ({}); falling back to commit status", limit(e.getMessage(), 160));
            try {
                String state = conclusion.equals("success") ? "success" : "failure";
                return githubClient.createCommitStatus(review.getRepoOwner(), review.getRepoName(),
                        review.getHeadSha(), state, "MergeGuard " + review.getVerdict().name()
                                + " · " + review.getScore() + "/100", "MergeGuard / risk review");
            } catch (Exception second) {
                log.warn("commit status skipped: {}", second.getMessage());
                return null;
            }
        }
    }

    private boolean alreadyReported(FindingDraft finding, List<Finding> previous) {
        for (Finding old : previous) {
            if (old.getFilePath().equals(finding.filePath()) && old.getCategory().equals(finding.category())) {
                if (finding.lineNumber() == null || old.getLineNumber() == null) {
                    return true;
                }
                if (Math.abs(finding.lineNumber() - old.getLineNumber()) <= 3) {
                    return true;
                }
            }
        }
        return false;
    }

    private String inlineBody(FindingDraft finding) {
        StringBuilder builder = new StringBuilder();
        builder.append("**").append(finding.severity().name()).append(" · ").append(finding.category())
                .append("** ").append(finding.title()).append("\n\n");
        if (!finding.evidence().isBlank()) {
            builder.append("```\n").append(limit(finding.evidence(), 600)).append("\n```\n\n");
        }
        if (!finding.suggestion().isBlank()) {
            builder.append("\u5efa\u8bae\uff1a").append(finding.suggestion()).append("\n\n");
        }
        builder.append("<sub>MergeGuard");
        if (finding.ruleId() != null) {
            builder.append(" \u00b7 ").append(finding.ruleId());
        }
        builder.append("</sub>");
        return builder.toString();
    }

    private String summaryMarkdown(Review review, List<FindingDraft> findings, GhPullRequest pr) {
        long blockers = findings.stream().filter(f -> f.severity() == Severity.BLOCKER).count();
        long majors = findings.stream().filter(f -> f.severity() == Severity.MAJOR).count();
        long minors = findings.stream().filter(f -> f.severity() == Severity.MINOR).count();
        StringBuilder builder = new StringBuilder();
        builder.append("## MergeGuard \u00b7 ").append(review.getVerdict()).append("\uff08")
                .append(review.getScore()).append("/100\uff09\n\n");
        builder.append("`").append(review.getRepoOwner()).append("/").append(review.getRepoName())
                .append("#").append(review.getPrNumber()).append("`")
                .append(" \u00b7 ").append(findings.size()).append(" \u9879\u53d1\u73b0")
                .append(" \u00b7 ").append(blockers).append(" \u963b\u65ad / ").append(majors)
                .append(" \u9ad8\u4f18 / ").append(minors).append(" \u63d0\u9192")
                .append(" \u00b7 ").append(review.getDurationMs()).append(" ms")
                .append(" \u00b7 ").append(review.getInputTokens() + review.getOutputTokens()).append(" tokens")
                .append(" \u00b7 \u6a21\u578b\u8986\u76d6 ").append(review.getLlmCoverage()).append("%\n\n");
        if (review.getSummary() != null && !review.getSummary().isBlank()) {
            builder.append("> ").append(review.getSummary().replace("\n", " ")).append("\n\n");
        }
        if (!findings.isEmpty()) {
            builder.append("| \u7ea7\u522b | \u6587\u4ef6 | \u95ee\u9898 |\n|---|---|---|\n");
            int rows = 0;
            for (FindingDraft finding : findings) {
                if (rows++ >= 12) break;
                builder.append("| ").append(finding.severity().name()).append(" | `")
                        .append(escape(finding.filePath())).append("` | ")
                        .append(escape(finding.title())).append(" |\n");
            }
            if (findings.size() > 12) {
                builder.append("\n\u5176\u4f59 ").append(findings.size() - 12)
                        .append(" \u9879\u89c1 MergeGuard \u63a7\u5236\u53f0\u3002\n");
            }
            builder.append("\n");
        }
        builder.append("<sub>\u89c4\u5219\u5f15\u64ce + \u6a21\u578b\u8bed\u4e49\u5ba1\u67e5\uff1b\u53ea\u505a\u5408\u5e76\u524d\u98ce\u9669\u63d0\u793a\uff0c\u4e0d\u81ea\u52a8\u5408\u5e76\u3002</sub>");
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
        return new Dtos.PageDto<>(items, result.getTotalElements(), safePage, safeSize, result.getTotalPages());
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

    public void feedback(long findingId, String status, String note) {
        Finding finding = findingRepository.findById(findingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "finding not found"));
        FindingStatus parsed;
        try {
            parsed = FindingStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown feedback status");
        }
        finding.setStatus(parsed);
        finding.setFeedbackNote(limit(note, 1000));
        finding.setFeedbackAt(Instant.now());
        findingRepository.save(finding);
        meterRegistry.counter("mergeguard.feedback", "status", parsed.name()).increment();
    }

    public Dtos.QualityDto quality() {
        List<Dtos.QualityRuleDto> rules = new ArrayList<>();
        long total = 0;
        long accepted = 0;
        long dismissed = 0;
        long fixed = 0;
        for (Object[] row : findingRepository.ruleQuality()) {
            String ruleId = (String) row[0];
            long count = number(row[1]);
            long acc = number(row[2]);
            long dis = number(row[3]);
            long fix = number(row[4]);
            long open = number(row[5]);
            double avgConfidence = row[6] == null ? 0 : ((Number) row[6]).doubleValue();
            long judged = acc + dis + fix;
            double rate = judged == 0 ? 0 : (double) (acc + fix) / judged;
            rules.add(new Dtos.QualityRuleDto(ruleId, count, acc, dis, fix, open, rate, avgConfidence));
            total += count;
            accepted += acc;
            dismissed += dis;
            fixed += fix;
        }
        long judged = accepted + dismissed + fixed;
        double rate = judged == 0 ? 0 : (double) (accepted + fixed) / judged;
        rules.sort(Comparator.comparingLong(Dtos.QualityRuleDto::total).reversed());
        return new Dtos.QualityDto(total, judged, accepted, dismissed, fixed, rate, rules);
    }

    public Dtos.DiffDto diff(long reviewId) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "review not found"));
        List<Dtos.DiffFileDto> files = reviewFileRepository.findByReviewIdOrderByFilePathAsc(reviewId).stream()
                .map(file -> new Dtos.DiffFileDto(file.getFilePath(), file.getAdditions(), file.getDeletions(),
                        file.getPatch()))
                .toList();
        return new Dtos.DiffDto(reviewId, review.getHeadSha(), files);
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
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
        if (llm.summary() != null && !llm.summary().isBlank()) {
            return limit(llm.summary(), 280);
        }
        long blockers = findings.stream().filter(f -> f.severity() == Severity.BLOCKER).count();
        long majors = findings.stream().filter(f -> f.severity() == Severity.MAJOR).count();
        if (findings.isEmpty()) {
            return "\u672a\u53d1\u73b0\u963b\u65ad\u6216\u9ad8\u4f18\u98ce\u9669\uff0c\u53ef\u4ee5\u5408\u5e76\uff1b\u4ecd\u5efa\u8bae\u4eba\u5de5\u786e\u8ba4\u4e1a\u52a1\u8bed\u4e49\u3002";
        }
        return "\u5171 " + findings.size() + " \u9879\u53d1\u73b0\uff0c" + blockers + " \u4e2a\u963b\u65ad\u3001" + majors
                + " \u4e2a\u9ad8\u4f18\uff1b" + (review.getVerdict() == Verdict.BLOCK
                ? "\u4e0d\u5efa\u8bae\u5408\u5e76\u3002" : "\u5efa\u8bae\u4fee\u590d\u540e\u518d\u5408\u5e76\u3002");
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
        entities = new ArrayList<>(entities);
        entities.sort(Comparator.comparingInt((Finding f) -> f.getSeverity().ordinal())
                .thenComparing(f -> f.getLineNumber() == null ? Integer.MAX_VALUE : f.getLineNumber()));
        List<Dtos.FindingDto> findings = entities.stream()
                .map(f -> new Dtos.FindingDto(f.getId(), f.getFilePath(), f.getLineNumber(),
                        f.getSeverity().name(), f.getCategory(), f.getTitle(), f.getEvidence(),
                        f.getSuggestion(), f.getSource().name(), f.getConfidence(), f.getRuleId(),
                        f.getStatus().name(), f.getFeedbackNote(), f.getFeedbackAt()))
                .toList();
        return new Dtos.ReviewDetailDto(review.getId(), review.repoFullName(), review.getPrNumber(),
                review.getTitle(), review.getAuthor(), review.getBaseRef(), review.getHeadSha(),
                review.getVerdict().name(), review.getScore(), review.getSummary(), review.getLlmModel(),
                review.getDurationMs(), review.getInputTokens(), review.getOutputTokens(), review.isPublished(),
                review.getPublishError(), review.getCreatedAt(), review.getPrUrl(), findings,
                nullToZero(review.getDiffMs()), nullToZero(review.getRuleMs()), nullToZero(review.getLlmMs()),
                nullToZero(review.getPublishMs()), review.getLlmChunks(), review.getLlmCoverage(),
                review.getInjectionNeutralized());
    }

    private long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    private String limit(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record PublishOutcome(boolean published, String error, Long summaryCommentId, Long checkRunId) {
    }
}
