package com.micaftic.morpher.cloud.client;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Per authenticated client, bounded metadata cache; never prefetches model bytes or pages. */
public final class CloudCatalogPageCache {
    private record Entry(CompletableFuture<CloudAssetPage> future, long expiresAt, long generation) { }
    private final LinkedHashMap<String, Entry> pages = new LinkedHashMap<>(16, 0.75f, true);
    private final int capacity;
    private final long ttl;
    private final LongSupplier clock;
    private long generation;
    public CloudCatalogPageCache() { this(8, 60_000, System::currentTimeMillis); }
    CloudCatalogPageCache(int capacity, long ttl, LongSupplier clock) {
        this.capacity = Math.max(1, capacity); this.ttl = Math.max(1, ttl); this.clock = clock;
    }
    public synchronized CompletableFuture<CloudAssetPage> get(String key, Supplier<CompletableFuture<CloudAssetPage>> fetch) {
        long now = clock.getAsLong();
        pages.entrySet().removeIf(e -> e.getValue().future.isDone() && e.getValue().expiresAt <= now);
        Entry cached = pages.get(key);
        if (cached != null) return cached.future;
        var result = new CompletableFuture<CloudAssetPage>();
        var entry = new Entry(result, now + ttl, generation);
        pages.put(key, entry);
        while (pages.size() > capacity) pages.remove(pages.keySet().iterator().next());
        try {
            fetch.get().whenComplete((page, failure) -> {
                synchronized (this) {
                    if (failure != null && pages.get(key) == entry) pages.remove(key);
                    else if (failure == null && entry.generation == generation && pages.get(key) == entry)
                        pages.put(key, new Entry(result, clock.getAsLong() + ttl, generation));
                }
                if (failure == null) result.complete(page); else result.completeExceptionally(failure);
            });
        } catch (RuntimeException failure) { pages.remove(key); result.completeExceptionally(failure); }
        return result;
    }
    public synchronized void clear() { generation++; pages.clear(); }
    synchronized int size() { return pages.size(); }
}
