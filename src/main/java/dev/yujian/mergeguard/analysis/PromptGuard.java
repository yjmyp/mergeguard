package dev.yujian.mergeguard.analysis;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Treats pull-request text as untrusted input. A PR author can otherwise write
 * "ignore previous instructions" inside a comment and steer the reviewer model.
 */
public final class PromptGuard {

    public static final String OPEN_TAG = "<UNTRUSTED_PULL_REQUEST>";
    public static final String CLOSE_TAG = "</UNTRUSTED_PULL_REQUEST>";

    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("(?i)(ignore|disregard|forget|override|bypass)\\s+(all\\s+)?"
                    + "(previous|prior|above|system|developer)\\s+(instruction|prompt|message|rule)s?"),
            Pattern.compile("(?i)(忽略|无视|忘记|绕过|覆盖).{0,12}(之前|以上|前述|系统).{0,12}(指令|提示|规则|要求)"),
            Pattern.compile("(?i)you\\s+are\\s+now\\s+(a|an|the)?\\s*\\w+"),
            Pattern.compile("(?i)(reveal|print|repeat|show)\\s+(your|the)\\s+(system\\s+)?prompt"),
            Pattern.compile("(?i)^\\s*(system|developer|assistant)\\s*:"),
            Pattern.compile("(?i)<\\s*/?\\s*(system|developer|assistant)\\s*>")
    );

    private PromptGuard() {
    }

    public static boolean isInjectionAttempt(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    public static Result neutralize(String text) {
        if (text == null) {
            return new Result("", 0);
        }
        String withoutCloseTag = text.replace(CLOSE_TAG, "</UNTRUSTED_PULL_REQUEST_BLOCKED>")
                .replace(OPEN_TAG, "<UNTRUSTED_PULL_REQUEST_BLOCKED>");
        StringBuilder builder = new StringBuilder(withoutCloseTag.length() + 64);
        int count = 0;
        for (String line : withoutCloseTag.split("\\R", -1)) {
            if (isInjectionAttempt(line)) {
                builder.append("[NEUTRALIZED-INSTRUCTION] ").append(line).append('\n');
                count++;
            } else {
                builder.append(line).append('\n');
            }
        }
        return new Result(builder.toString(), count);
    }

    public record Result(String text, int neutralized) {
    }
}
