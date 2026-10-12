package com.micaftic.morpher.cloud.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudAssetPageCursorGuardTest {
    @Test
    void allowsManyPagesButStopsRepeatedOrMissingCursors() {
        var guard = new CloudAssetPageCursorGuard();
        String previous = null;
        for (int i = 0; i < 1000; i++) {
            String next = "cursor-" + i;
            assertTrue(guard.canContinue(previous, next, true));
            previous = next;
        }
        assertFalse(guard.canContinue(previous, previous, true));
        assertFalse(guard.canContinue(previous, null, true));
        assertFalse(guard.canContinue(previous, "cursor-4", true));
        assertFalse(guard.canContinue(previous, "unused", false));
        guard.reset();
        assertTrue(guard.canContinue(null, "cursor-0", true));
    }
}
