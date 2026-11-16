package dev.yujian.mergeguard.analysis;

import dev.yujian.mergeguard.diff.FileDiff;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class DiffChunker {

    private final int chunkChars;
    private final int maxChunks;
    private final int perFileChars;

    public DiffChunker(int chunkChars, int maxChunks, int perFileChars) {
        this.chunkChars = Math.max(1000, chunkChars);
        this.maxChunks = Math.max(1, maxChunks);
        this.perFileChars = Math.max(500, perFileChars);
    }

    public Plan chunk(List<FileDiff> diffs) {
        int totalAdded = 0;
        for (FileDiff diff : diffs) {
            totalAdded += diff.addedLines().size();
        }
        List<Chunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        Set<String> currentFiles = new LinkedHashSet<>();
        int currentLines = 0;
        int planned = 0;
        boolean full = false;

        for (FileDiff diff : diffs) {
            if (diff.addedLines().isEmpty()) {
                continue;
            }
            int perFile = 0;
            boolean headerAdded = false;
            for (FileDiff.AddedLine line : diff.addedLines()) {
                String content = line.content();
                if (content.length() > 500) {
                    content = content.substring(0, 500) + " …";
                }
                String rendered = "+L" + line.lineNumber() + "| " + content + "\n";
                if (perFile + rendered.length() > perFileChars) {
                    break;
                }
                if (!headerAdded) {
                    current.append("### ").append(diff.filename()).append(" (")
                            .append(diff.addedLines().size()).append(" added lines)\n");
                    currentFiles.add(diff.filename());
                    headerAdded = true;
                }
                if (current.length() + rendered.length() > chunkChars) {
                    chunks.add(new Chunk(chunks.size() + 1, current.toString(), currentLines, List.copyOf(currentFiles)));
                    if (chunks.size() >= maxChunks) {
                        full = true;
                        break;
                    }
                    current = new StringBuilder();
                    currentFiles = new LinkedHashSet<>();
                    currentLines = 0;
                    current.append("### ").append(diff.filename()).append(" (continued)\n");
                    currentFiles.add(diff.filename());
                }
                current.append(rendered);
                perFile += rendered.length();
                currentLines++;
                planned++;
            }
            if (full) {
                break;
            }
        }
        if (!full && current.length() > 0 && chunks.size() < maxChunks) {
            chunks.add(new Chunk(chunks.size() + 1, current.toString(), currentLines, List.copyOf(currentFiles)));
        }
        int coverage = totalAdded == 0 ? 100 : Math.min(100, (int) Math.round(planned * 100.0 / totalAdded));
        List<Chunk> renumbered = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            renumbered.add(new Chunk(i + 1, c.text(), c.lines(), c.files()));
        }
        return new Plan(renumbered, totalAdded, planned, coverage);
    }

    public record Chunk(int index, String text, int lines, List<String> files) {
    }

    public record Plan(List<Chunk> chunks, int totalAddedLines, int plannedLines, int coveragePercent) {
        public boolean empty() {
            return chunks.isEmpty();
        }
    }
}
