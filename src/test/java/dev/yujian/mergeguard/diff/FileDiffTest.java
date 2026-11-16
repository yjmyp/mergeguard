package dev.yujian.mergeguard.diff;

import static org.assertj.core.api.Assertions.assertThat;

import dev.yujian.mergeguard.github.GhFile;
import org.junit.jupiter.api.Test;

class FileDiffTest {

    @Test
    void mapsAddedLinesToNewFileNumbers() {
        GhFile file = new GhFile("a.java", "modified", 2, 1, 3,
                "@@ -1,3 +1,4 @@\n line1\n-old\n+new1\n+new2\n line3\n", null);
        FileDiff diff = FileDiff.parse(file);
        assertThat(diff.addedLines()).extracting(FileDiff.AddedLine::lineNumber).containsExactly(2, 3);
        assertThat(diff.addedLines()).extracting(FileDiff.AddedLine::content).containsExactly("new1", "new2");
    }

    @Test
    void supportsMultipleHunks() {
        GhFile file = new GhFile("a.java", "modified", 2, 0, 2,
                "@@ -1,2 +1,2 @@\n a\n+b\n@@ -20,2 +20,3 @@\n c\n+d\n+e\n", null);
        FileDiff diff = FileDiff.parse(file);
        assertThat(diff.addedLineNumbers()).containsExactly(2, 21, 22);
    }

    @Test
    void handlesBinaryOrMissingPatch() {
        GhFile file = new GhFile("logo.png", "added", 0, 0, 0, null, null);
        assertThat(FileDiff.parse(file).addedLines()).isEmpty();
    }
}
