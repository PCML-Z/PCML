package com.pmcl.core.launch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MemoryBudgetTest {

    @Test
    void leavesRoomForTheSystem() {
        long eightGb = 8L * 1024 * 1024 * 1024;
        assertEquals(4096, MemoryBudget.capMax(4096, eightGb));
        assertEquals(8 * 1024 - MemoryBudget.RESERVE_MB, MemoryBudget.capMax(32 * 1024, eightGb));
    }

    @Test
    void unknownPhysicalMemoryIsNotClamped() {
        assertEquals(16384, MemoryBudget.capMax(16384, 0));
    }

    @Test
    void minDoesNotExceedCappedMax() {
        assertEquals(2048, MemoryBudget.capMin(4096, 2048));
        assertEquals(1024, MemoryBudget.capMin(1024, 4096));
    }

    @Test
    void heapFlagsAboveCapAreRewritten() {
        assertEquals("-Xmx6656m", MemoryBudget.clampHeapFlag("-Xmx16G", 6656));
        assertEquals("-Xms4096m", MemoryBudget.clampHeapFlag("-Xms8192m", 4096));
        assertEquals("-Xmx2g", MemoryBudget.clampHeapFlag("-Xmx2g", 4096));
        assertEquals("-XX:+UseG1GC", MemoryBudget.clampHeapFlag("-XX:+UseG1GC", 4096));
    }
}
