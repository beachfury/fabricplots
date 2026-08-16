package com.fabricplots.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicFilesTest {
    @TempDir Path temp;

    @Test
    void keepsLastKnownGoodBackupAndReadsPrimary() throws Exception {
        Path file = temp.resolve("plots.txt");
        AtomicFiles.writeLines(file, List.of("first", "café"));
        AtomicFiles.writeLines(file, List.of("second"));

        assertEquals(List.of("second"), AtomicFiles.readLines(file));
        assertEquals(List.of("first", "café"), Files.readAllLines(temp.resolve("plots.txt.bak")));
        assertFalse(Files.exists(temp.resolve("plots.txt.tmp")));
    }

    @Test
    void fallsBackToBackupWhenPrimaryIsMissing() throws Exception {
        Path file = temp.resolve("returns.txt");
        AtomicFiles.writeLines(file, List.of("safe"));
        AtomicFiles.writeLines(file, List.of("new"));
        Files.delete(file);

        assertTrue(AtomicFiles.exists(file));
        assertEquals(List.of("safe"), AtomicFiles.readLines(file));
    }
}
