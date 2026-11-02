package com.fs20modconverterpro.archive;

/** Totais informados após uma extração concluída. */
public final class ArchiveExtractionResult {
    private final int extractedEntries;
    private final long extractedBytes;

    public ArchiveExtractionResult(int extractedEntries, long extractedBytes) {
        this.extractedEntries = extractedEntries;
        this.extractedBytes = extractedBytes;
    }

    public int getExtractedEntries() {
        return extractedEntries;
    }

    public long getExtractedBytes() {
        return extractedBytes;
    }
}
