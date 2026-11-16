package dev.yujian.mergeguard.github;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GhFile(
        String filename,
        String status,
        int additions,
        int deletions,
        int changes,
        String patch,
        @JsonProperty("previous_filename") String previousFilename
) {
}
