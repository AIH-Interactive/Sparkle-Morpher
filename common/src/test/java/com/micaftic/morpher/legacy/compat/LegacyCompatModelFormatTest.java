package com.micaftic.morpher.legacy.compat;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyCompatModelFormatTest {
    @TempDir Path tempDir;

    @Test
    void rejectsMissingOrMalformedLegacyContainers() throws Exception {
        assertEquals(-1, LegacyCompatModelFormat.detectCryptoVersion((byte[]) null));
        assertEquals(-1, LegacyCompatModelFormat.detectCryptoVersion(new byte[0]));
        assertTrue(LegacyCompatModelFormat.read(new byte[0]).isEmpty());
        assertEquals(Map.of(), LegacyCompatModelFormat.read(new byte[23]));
    }

    @Test
    void detectsVersionFromOnlyTheFileHeader() throws Exception {
        Path ysm = tempDir.resolve("header.ysm");
        Files.write(ysm, new byte[]{0x59, 0x53, 0x47, 0x50, 0, 0, 0, 2, 9, 8, 7});
        assertEquals(2, LegacyCompatModelFormat.detectCryptoVersion(ysm));
    }
}
