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

    public long postIssueComment(String owner, String name, int number, String body) {
        var node = restClient.post()
                .uri("/repos/{owner}/{name}/issues/{number}/comments", owner, name, number)
                .body(Map.of("body", body))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new GitHubException(response.getStatusCode().value(), readBody(response.getBody()));
                })
                .body(Map.class);
        Object id = node == null ? null : node.get("id");
        return id instanceof Number num ? num.longValue() : 0L;
    }

    public java.util.List<GhComment> listIssueComments(String owner, String name, int number) {
        java.util.List<GhComment> comments = new java.util.ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            int pageNumber = page;
            java.util.List<java.util.Map<String, Object>> current = restClient.get()
                    .uri(uri -> uri.path("/repos/{owner}/{name}/issues/{number}/comments")
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
            for (java.util.Map<String, Object> item : current) {
                Object id = item.get("id");
                Object body = item.get("body");
                Object user = item.get("user");
                String login = null;
                if (user instanceof java.util.Map<?, ?> map && map.get("login") != null) {
                    login = map.get("login").toString();
                }
                comments.add(new GhComment(id instanceof Number num ? num.longValue() : 0L,
                        body == null ? "" : body.toString(), login));
            }
            if (current.size() < 100) {
                break;
            }
        }
        return comments;
    }

    public long updateIssueComment(String owner, String name, long commentId, String body) {
        var node = restClient.patch()
                .uri("/repos/{owner}/{name}/issues/comments/{commentId}", owner, name, commentId)
                .body(Map.of("body", body))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new GitHubException(response.getStatusCode().value(), readBody(response.getBody()));
                })
                .body(Map.class);
        Object id = node == null ? null : node.get("id");
        return id instanceof Number num ? num.longValue() : commentId;
    }

    public long createCheckRun(String owner, String name, String headSha, String conclusion,
                               String title, String summary, java.util.List<CheckAnnotation> annotations,
                               String externalId) {
        java.util.List<java.util.Map<String, Object>> items = new java.util.ArrayList<>();
        for (CheckAnnotation annotation : annotations) {
            items.add(java.util.Map.of(
                    "path", annotation.path(),
                    "start_line", annotation.line(),
                    "end_line", annotation.line(),
                    "annotation_level", annotation.level(),
                    "title", annotation.title(),
                    "message", annotation.message()));
        }
        java.util.Map<String, Object> output = java.util.Map.of(
                "title", title,
                "summary", summary,
                "annotations", items);
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("name", "MergeGuard");
        payload.put("head_sha", headSha);
        payload.put("status", "completed");
        payload.put("conclusion", conclusion);
        payload.put("external_id", externalId);
        payload.put("output", output);
        var node = restClient.post()
                .uri("/repos/{owner}/{name}/check-runs", owner, name)
                .body(payload)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new GitHubException(response.getStatusCode().value(), readBody(response.getBody()));
                })
                .body(Map.class);
        Object id = node == null ? null : node.get("id");
        return id instanceof Number num ? num.longValue() : 0L;
    }

    public long createCommitStatus(String owner, String name, String sha, String state,
                                   String description, String context) {
        var node = restClient.post()
                .uri("/repos/{owner}/{name}/statuses/{sha}", owner, name, sha)
                .body(Map.of("state", state, "description", description, "context", context))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new GitHubException(response.getStatusCode().value(), readBody(response.getBody()));
                })
                .body(Map.class);
        Object id = node == null ? null : node.get("id");
        return id instanceof Number num ? num.longValue() : 0L;
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

    public record GhComment(long id, String body, String userLogin) {
    }

    public record CheckAnnotation(String path, int line, String level, String title, String message) {
    }
}
