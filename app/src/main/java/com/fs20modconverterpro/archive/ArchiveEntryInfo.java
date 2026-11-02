package com.fs20modconverterpro.archive;

/** Metadados de uma entrada exibidos na inspeção do arquivo. */
public final class ArchiveEntryInfo {
    private final String name;
    private final long uncompressedSize;
    private final boolean directory;
    private final boolean safe;

    public ArchiveEntryInfo(String name, long uncompressedSize, boolean directory, boolean safe) {
        this.name = name;
        this.uncompressedSize = uncompressedSize;
        this.directory = directory;
        this.safe = safe;
    }

    public String getName() {
        return name;
    }

    public long getUncompressedSize() {
        return uncompressedSize;
    }

    public boolean isDirectory() {
        return directory;
    }

    public boolean isSafe() {
        return safe;
    }
}
