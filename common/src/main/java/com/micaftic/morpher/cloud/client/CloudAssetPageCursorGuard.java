package com.micaftic.morpher.cloud.client;

import java.util.HashSet;
import java.util.Set;

/** Stops a malformed or unstable Cloud cursor from making the client fetch the same pages forever. */
public final class CloudAssetPageCursorGuard {
    private final Set<String> returnedCursors = new HashSet<>();

    public synchronized boolean canContinue(String requestedCursor, String nextCursor, boolean hasMore) {
        if (!hasMore || nextCursor == null || nextCursor.isBlank()) return false;
        if (nextCursor.equals(requestedCursor)) return false;
        return returnedCursors.add(nextCursor);
    }

    public synchronized void reset() {
        returnedCursors.clear();
    }
}
