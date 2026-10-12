package com.micaftic.morpher.core.storage;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Moves Cloud runtime imports out of the legacy custom-model directory. */
public final class CloudModelStorageMigration {
    private static final Pattern CLOUD_ID = Pattern.compile("cloud_[0-9a-f]{64}");
    private static final String[] EXTENSIONS = {".ysm", ".zip", ".bbmodel", ".gltf", ".glb"};

    private CloudModelStorageMigration() { }

    /**
     * Old releases persisted selected Cloud models beside user imports. Only the
     * reserved, content-addressed Cloud ID format is migrated. Existing targets
     * are preserved; a collision leaves the legacy source untouched for recovery.
     */
    public static List<Path> migrate(Path legacyCustomRoot, Path cloudRoot) throws IOException {
        if (legacyCustomRoot == null || !Files.isDirectory(legacyCustomRoot)) return List.of();
        Files.createDirectories(cloudRoot);
        List<Path> moved = new ArrayList<>();
        try (var children = Files.list(legacyCustomRoot)) {
            for (Path source : children.toList()) {
                String name = source.getFileName().toString();
                String stem = stem(name);
                if (!CLOUD_ID.matcher(stem).matches()) continue;
                Path target = cloudRoot.resolve(name);
                if (Files.exists(target)) continue;
                try {
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(source, target);
                }
                moved.add(target);
            }
        }
        return List.copyOf(moved);
    }

    private static String stem(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String extension : EXTENSIONS) {
            if (lower.endsWith(extension)) return fileName.substring(0, fileName.length() - extension.length());
        }
        return fileName;
    }
}
