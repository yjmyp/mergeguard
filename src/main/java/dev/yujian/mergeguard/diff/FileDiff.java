package dev.yujian.mergeguard.diff;

import dev.yujian.mergeguard.github.GhFile;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record FileDiff(GhFile file, List<AddedLine> addedLines) {

    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

    public String filename() { return file.filename(); }

    public Set<Integer> addedLineNumbers() {
        Set<Integer> numbers = new LinkedHashSet<>();
        for (AddedLine line : addedLines) {
            numbers.add(line.lineNumber());
        }
        return numbers;
    }

    public static FileDiff parse(GhFile file) {
        List<AddedLine> added = new ArrayList<>();
        String patch = file.patch();
        if (patch == null || patch.isBlank()) {
            return new FileDiff(file, added);
        }
        int newLine = 0;
        boolean inHunk = false;
        for (String raw : patch.split("\\R", -1)) {
            Matcher matcher = HUNK.matcher(raw);
            if (matcher.matches()) {
                newLine = Integer.parseInt(matcher.group(3));
                inHunk = true;
                continue;
            }
            if (!inHunk || raw.isEmpty()) {
                continue;
            }
            char first = raw.charAt(0);
            if (first == '+') {
                if (!raw.startsWith("+++")) {
                    added.add(new AddedLine(newLine, raw.substring(1)));
                }
                newLine++;
            } else if (first == ' ') {
                newLine++;
            } else if (first == '-') {
                // deletion does not advance the new-file counter
            }
        }
        return new FileDiff(file, added);
    }

    public record AddedLine(int lineNumber, String content) {
    }
}
