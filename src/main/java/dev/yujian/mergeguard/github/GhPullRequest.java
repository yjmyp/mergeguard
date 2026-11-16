package dev.yujian.mergeguard.github;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GhPullRequest(
        long number,
        String title,
        String body,
        GhUser user,
        GhHead head,
        GhBase base,
        @JsonProperty("html_url") String htmlUrl
) {
}
