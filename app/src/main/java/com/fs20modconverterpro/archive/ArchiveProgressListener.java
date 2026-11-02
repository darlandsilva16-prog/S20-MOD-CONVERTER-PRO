package com.fs20modconverterpro.archive;

/** Recebe atualizações durante a leitura ou extração de um arquivo. */
public interface ArchiveProgressListener {
    void onProgress(int processedEntries, long processedBytes, String currentEntry);
}
