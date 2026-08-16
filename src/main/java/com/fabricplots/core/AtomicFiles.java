package com.fabricplots.core;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

/** Crash-resistant UTF-8 line-file persistence with a last-known-good backup. */
public final class AtomicFiles {
    private AtomicFiles() {}

    public static List<String> readLines(Path target) throws IOException {
        Path backup = backup(target);
        if (!Files.exists(target) && Files.exists(backup)) return Files.readAllLines(backup, StandardCharsets.UTF_8);
        try {
            return Files.readAllLines(target, StandardCharsets.UTF_8);
        } catch (IOException primary) {
            if (!Files.exists(backup)) throw primary;
            try {
                return Files.readAllLines(backup, StandardCharsets.UTF_8);
            } catch (IOException secondary) {
                primary.addSuppressed(secondary);
                throw primary;
            }
        }
    }

    /** True when either the primary file or its last-known-good backup can be loaded. */
    public static boolean exists(Path target) {
        return Files.exists(target) || Files.exists(backup(target));
    }

    public static void writeLines(Path target, Iterable<String> lines) throws IOException {
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Path backup = backup(target);
        try {
            try (var writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                for (String line : lines) {
                    writer.write(line);
                    writer.newLine();
                }
            }
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            if (Files.exists(target)) {
                Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static Path backup(Path target) {
        return target.resolveSibling(target.getFileName() + ".bak");
    }
}
