package com.micaftic.morpher.cloud.client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CloudCatalogCursorHistoryTest {
    @Test void remembersOnlyVisitedCursorsAndDoesNotAdvanceOnFailedRequests() {
        var history = new CloudCatalogCursorHistory();
        assertEquals("", history.current());
        assertFalse(history.hasPrevious());
        history.accept("", "b", true);
        assertEquals("", history.current());
        assertEquals("b", history.next());
        history.accept("b", "c", true);
        assertEquals(1, history.index());
        assertEquals("", history.previous());
        assertEquals("b", history.current());
        history.accept("", "b", true);
        assertEquals(0, history.index());
        history.accept("b", "b", true);
        assertNull(history.next(), "repeated cursor must stop paging");
        history.clear();
        assertEquals(0, history.index());
        assertFalse(history.hasPrevious());
    }
}
