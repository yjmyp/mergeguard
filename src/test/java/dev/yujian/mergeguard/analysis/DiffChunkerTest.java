package dev.yujian.mergeguard.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import dev.yujian.mergeguard.diff.FileDiff;
import dev.yujian.mergeguard.github.GhFile;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiffChunkerTest {

    private static FileDiff diff(String name, int lines) {
        StringBuilder patch = new StringBuilder("@@ -1,1 +1,").append(lines).append(" @@\n");
        for (int i = 1; i <= lines; i++) {
            patch.append("+").append("X".repeat(80)).append(i).append("\n");
        }
        return FileDiff.parse(new GhFile(name, "modified", lines, 0, lines, patch.toString(), null));
    }

    @Test
    void keepsLineNumbersInPrompt() {
        var plan = new DiffChunker(6000, 4, 3000).chunk(List.of(diff("A.java", 3)));
        assertThat(plan.chunks()).hasSize(1);
        assertThat(plan.chunks().get(0).text()).contains("+L1|").contains("+L2|").contains("+L3|");
        assertThat(plan.coveragePercent()).isEqualTo(100);
    }

    @Test
    void splitsLargeDiffIntoBoundedChunks() {
        var plan = new DiffChunker(1200, 8, 3000).chunk(List.of(diff("Big.java", 80)));
        assertThat(plan.chunks().size()).isGreaterThan(1).isLessThanOrEqualTo(8);
        for (var chunk : plan.chunks()) {
            assertThat(chunk.text().length()).isLessThanOrEqualTo(1400);
        }
    }

    @Test
    void capsChunkCountAndReportsCoverage() {
        List<FileDiff> diffs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            diffs.add(diff("F" + i + ".java", 20));
        }
        var plan = new DiffChunker(1200, 2, 3000).chunk(diffs);
        assertThat(plan.chunks()).hasSize(2);
        assertThat(plan.coveragePercent()).isLessThan(100);
        assertThat(plan.totalAddedLines()).isEqualTo(240);
    }

    @Test
    void emptyForFilesWithoutAddedLines() {
        var plan = new DiffChunker(6000, 4, 3000).chunk(List.of());
        assertThat(plan.empty()).isTrue();
        assertThat(plan.coveragePercent()).isEqualTo(100);
    }
}
