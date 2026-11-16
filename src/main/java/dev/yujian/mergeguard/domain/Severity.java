package dev.yujian.mergeguard.domain;

public enum Severity {
    BLOCKER(40),
    MAJOR(12),
    MINOR(4),
    INFO(1);

    private final int penalty;

    Severity(int penalty) { this.penalty = penalty; }

    public int penalty() { return penalty; }

    public static Severity from(String value, Severity fallback) {
        if (value == null) return fallback;
        return switch (value.trim().toLowerCase()) {
            case "blocker", "critical", "high" -> BLOCKER;
            case "major", "medium", "error" -> MAJOR;
            case "minor", "low", "warning" -> MINOR;
            case "info", "note", "suggestion" -> INFO;
            default -> fallback;
        };
    }
}
