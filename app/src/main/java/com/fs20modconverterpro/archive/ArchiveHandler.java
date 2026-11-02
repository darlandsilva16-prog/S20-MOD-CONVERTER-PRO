package com.fs20modconverterpro.archive;

import java.io.IOException;
import java.io.InputStream;

/** Contrato extensível para formatos de mods, como ZIP e futuros arquivos GAR. */
public interface ArchiveHandler {
    boolean supports(String displayName, String mimeType);

    ArchiveInspection inspect(InputStream input, ArchiveProgressListener progressListener)
            throws IOException;

    ArchiveExtractionResult extract(
            InputStream input,
            ArchiveDestination destination,
            ArchiveProgressListener progressListener) throws IOException;
}
