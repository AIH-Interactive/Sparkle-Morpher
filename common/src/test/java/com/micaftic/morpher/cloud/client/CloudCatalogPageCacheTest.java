package com.micaftic.morpher.cloud.client;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class CloudCatalogPageCacheTest {
    @Test void fetchesOnlyRequestedPagesAndBoundsExpiredCache() {
        var clock = new AtomicLong();
        var calls = new AtomicInteger();
        var cache = new CloudCatalogPageCache(2, 100, clock::get);
        java.util.function.Supplier<CompletableFuture<CloudAssetPage>> fetch = () -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(new CloudAssetPage(List.of(), "next", true));
        };
        cache.get("first", fetch).join();
        assertEquals(1, calls.get(), "has_more must never trigger prefetch");
        cache.get("first", fetch).join();
        assertEquals(1, calls.get());
        cache.get("second", fetch).join();
        cache.get("third", fetch).join();
        assertEquals(2, cache.size());
        cache.get("first", fetch).join();
        assertEquals(4, calls.get());
        clock.set(101);
        cache.get("first", fetch).join();
        assertEquals(5, calls.get());
        cache.clear();
        assertEquals(0, cache.size());
    }
    @Test void coalescesInFlightPagesAndDoesNotKeepFailuresOrInvalidatedResults() {
        var cache = new CloudCatalogPageCache(2, 100, () -> 0L);
        var pending = new CompletableFuture<CloudAssetPage>();
        var first = cache.get("first", () -> pending);
        assertSame(first, cache.get("first", () -> { fail("duplicate fetch"); return null; }));
        cache.clear();
        pending.complete(new CloudAssetPage(List.of(), null, false));
        assertEquals(0, cache.size());
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> cache.get("failed", () -> CompletableFuture.failedFuture(new IllegalStateException())).join());
        cache.get("failed", () -> CompletableFuture.completedFuture(new CloudAssetPage(List.of(), null, false))).join();
        assertEquals(1, cache.size());
    }
}
