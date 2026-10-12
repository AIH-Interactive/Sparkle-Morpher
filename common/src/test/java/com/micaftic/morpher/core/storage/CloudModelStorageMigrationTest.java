package com.micaftic.morpher.core.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudModelStorageMigrationTest {
    @TempDir Path tempDir;

    @Test
    void migratesOnlyReservedCloudRuntimeIdsAndIsIdempotent() throws Exception {
        Path custom = Files.createDirectory(tempDir.resolve("custom"));
        Path cloud = tempDir.resolve("cloud_models");
        String cloudId = "cloud_" + "a".repeat(64);
        Path oldCloudModel = Files.writeString(custom.resolve(cloudId + ".ysm"), "cloud data");
        Path localModel = Files.writeString(custom.resolve("my-model.ysm"), "local data");

        var moved = CloudModelStorageMigration.migrate(custom, cloud);

        assertEquals(1, moved.size());
        assertTrue(Files.exists(cloud.resolve(cloudId + ".ysm")));
        assertFalse(Files.exists(oldCloudModel));
        assertTrue(Files.exists(localModel));
        assertTrue(CloudModelStorageMigration.migrate(custom, cloud).isEmpty());
    }
}
