package dev.yujian.mergeguard.github;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GhRepo(
        long id,
        String name,
        @JsonProperty("full_name") String fullName,
        GhUser owner,
        @JsonProperty("private") boolean privateRepo,
        @JsonProperty("html_url") String htmlUrl
) {
}
