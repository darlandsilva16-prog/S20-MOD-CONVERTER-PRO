package com.fs20modconverterpro.archive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Resultado resumido da inspeção de um arquivo compactado. */
public final class ArchiveInspection {
    private final List<ArchiveEntryInfo> entries;
    private final int totalEntries;
    private final int unsafeEntries;
    private final long totalUncompressedBytes;
    private final boolean truncated;

    public ArchiveInspection(
            List<ArchiveEntryInfo> entries,
            int totalEntries,
            int unsafeEntries,
            long totalUncompressedBytes,
            boolean truncated) {
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        this.totalEntries = totalEntries;
        this.unsafeEntries = unsafeEntries;
        this.totalUncompressedBytes = totalUncompressedBytes;
        this.truncated = truncated;
    }

    public List<ArchiveEntryInfo> getEntries() {
        return entries;
    }

    public int getTotalEntries() {
        return totalEntries;
    }

    public int getUnsafeEntries() {
        return unsafeEntries;
    }

    public long getTotalUncompressedBytes() {
        return totalUncompressedBytes;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public boolean canExtract() {
        return totalEntries > 0 && unsafeEntries == 0;
    }
}
