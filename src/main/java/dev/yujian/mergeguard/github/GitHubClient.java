package dev.yujian.mergeguard.github;

import dev.yujian.mergeguard.config.MergeGuardProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class GitHubClient {

    private final RestClient restClient;
    private final MergeGuardProperties properties;

    public GitHubClient(RestClient githubRestClient, MergeGuardProperties properties) {
        this.restClient = githubRestClient;
        this.properties = properties;
    }

    public boolean hasToken() {
        String token = properties.getGithub().getToken();
        return token != null && !token.isBlank();
    }

    public GhRepo getRepo(String owner, String name) {
        return restClient.get()
                .uri("/repos/{owner}/{name}", owner, name)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new GitHubException(response.getStatusCode().value(), readBody(response.getBody()));
                })
                .body(GhRepo.class);
    }

    public List<GhPullRequest> listOpenPulls(String owner, String name) {
        return restClient.get()
                .uri(uri -> uri.path("/repos/{owner}/{name}/pulls")
                        .queryParam("state", "open")
                        .queryParam("per_page", 30)
                        .build(owner, name))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new GitHubException(response.getStatusCode().value(), readBody(response.getBody()));
                })
                .body(new ParameterizedTypeReference<>() {});
    }

    public GhPullRequest getPull(String owner, String name, int number) {
        return restClient.get()
                .uri("/repos/{owner}/{name}/pulls/{number}", owner, name, number)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new GitHubException(response.getStatusCode().value(), readBody(response.getBody()));
                })
                .body(GhPullRequest.class);
    }

    public List<GhFile> listFiles(String owner, String name, int number) {
        List<GhFile> all = new java.util.ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            int pageNumber = page;
            List<GhFile> current = restClient.get()
                    .uri(uri -> uri.path("/repos/{owner}/{name}/pulls/{number}/files")
                            .queryParam("per_page", 100)
                            .queryParam("page", pageNumber)
                            .build(owner, name, number))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw new GitHubException(response.getStatusCode().value(), readBody(response.getBody()));
                    })
                    .body(new ParameterizedTypeReference<>() {});
            if (current == null || current.isEmpty()) {
                break;
            }
            all.addAll(current);
            if (current.size() < 100) {
                break;
            }
        }
        return all;
    }

    public long postReview(String owner, String name, int number, String commitId, String body,
                           List<ReviewComment> comments) {
        Map<String, Object> payload = Map.of(
                "commit_id", commitId,
                "body", body,
                "event", "COMMENT",
                "comments", comments);
        var node = restClient.post()
                .uri("/repos/{owner}/{name}/pulls/{number}/reviews", owner, name, number)
                .body(payload)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new GitHubException(response.getStatusCode().value(), readBody(response.getBody()));
                })
                .body(Map.class);
        Object id = node == null ? null : node.get("id");
        return id instanceof Number num ? num.longValue() : 0L;
    }

    public void postIssueComment(String owner, String name, int number, String body) {
        restClient.post()
                .uri("/repos/{owner}/{name}/issues/{number}/comments", owner, name, number)
                .body(Map.of("body", body))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new GitHubException(response.getStatusCode().value(), readBody(response.getBody()));
                })
                .toBodilessEntity();
    }

    private String readBody(java.io.InputStream inputStream) {
        try {
            byte[] bytes = inputStream.readAllBytes();
            String text = new String(bytes, StandardCharsets.UTF_8);
            return text.length() > 500 ? text.substring(0, 500) : text;
        } catch (IOException e) {
            return "unreadable error body";
        }
    }

    public record ReviewComment(String path, int line, String side, String body) {
    }
}
