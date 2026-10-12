package com.micaftic.morpher.cloud.client;
import java.util.ArrayList;
import java.util.List;

/** Visited cursors only; summaries remain in the bounded page cache. */
public final class CloudCatalogCursorHistory {
    private final List<String> cursors = new ArrayList<>(List.of(""));
    private int index;
    private String next;
    public String current() { return cursors.get(index); }
    public int index() { return index; }
    public boolean hasPrevious() { return index > 0; }
    public String previous() { return hasPrevious() ? cursors.get(index - 1) : null; }
    public String next() { return next; }
    public void accept(String requested, String returned, boolean hasMore) {
        requested = requested == null ? "" : requested;
        int visited = cursors.indexOf(requested);
        if (visited >= 0) index = visited;
        else {
            while (cursors.size() > index + 1) cursors.remove(cursors.size() - 1);
            cursors.add(requested); index++;
        }
        next = hasMore && returned != null && !returned.isBlank()
                && (cursors.indexOf(returned) < 0 || cursors.indexOf(returned) > index) ? returned : null;
    }
    public void clear() { cursors.clear(); cursors.add(""); index = 0; next = null; }
}
