package dev.yujian.mergeguard.web;

import dev.yujian.mergeguard.domain.RepoConfig;
import dev.yujian.mergeguard.github.GitHubClient;
import dev.yujian.mergeguard.github.GhRepo;
import dev.yujian.mergeguard.repo.RepoConfigRepository;
import dev.yujian.mergeguard.service.PollingService;
import dev.yujian.mergeguard.web.dto.Dtos;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/repos")
public class RepoController {

    private final RepoConfigRepository repoConfigRepository;
    private final GitHubClient githubClient;
    private final PollingService pollingService;

    public RepoController(RepoConfigRepository repoConfigRepository, GitHubClient githubClient,
                          PollingService pollingService) {
        this.repoConfigRepository = repoConfigRepository;
        this.githubClient = githubClient;
        this.pollingService = pollingService;
    }

    @GetMapping
    public List<Dtos.RepoDto> list() {
        return repoConfigRepository.findAllByOrderByFullNameAsc().stream().map(this::toDto).toList();
    }

    @PostMapping
    public Dtos.RepoDto add(@Valid @RequestBody Dtos.RepoRequest request) {
        GhRepo repo = githubClient.getRepo(request.owner(), request.name());
        RepoConfig config = repoConfigRepository.findByOwnerAndName(request.owner(), request.name())
                .orElseGet(RepoConfig::new);
        config.setId(repo.id());
        config.setOwner(request.owner());
        config.setName(request.name());
        config.setFullName(repo.fullName());
        config.setEnabled(true);
        config.setAutoReview(request.autoReview() == null || request.autoReview());
        return toDto(repoConfigRepository.save(config));
    }

    @PatchMapping("/{id}")
    public Dtos.RepoDto update(@PathVariable long id, @RequestBody Dtos.RepoUpdateRequest request) {
        RepoConfig config = repoConfigRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "repo not found"));
        if (request.enabled() != null) config.setEnabled(request.enabled());
        if (request.autoReview() != null) config.setAutoReview(request.autoReview());
        if (request.ignoredPaths() != null) config.setIgnoredPaths(request.ignoredPaths());
        if (request.disabledRules() != null) config.setDisabledRules(request.disabledRules());
        if (request.minPublishSeverity() != null) config.setMinPublishSeverity(request.minPublishSeverity());
        if (request.maxInlineComments() != null) config.setMaxInlineComments(request.maxInlineComments());
        return toDto(repoConfigRepository.save(config));
    }

    @PostMapping("/{id}/scan")
    public Dtos.ScanResult scan(@PathVariable long id) {
        RepoConfig config = repoConfigRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "repo not found"));
        return pollingService.scan(config, true);
    }

    private Dtos.RepoDto toDto(RepoConfig config) {
        return new Dtos.RepoDto(config.getId(), config.getOwner(), config.getName(), config.getFullName(),
                config.isEnabled(), config.isAutoReview(), config.getIgnoredPaths(), config.getDisabledRules(),
                config.getMinPublishSeverity(), config.getMaxInlineComments(), config.getUpdatedAt());
    }
}
