package com.fs20modconverterpro.archive;

import java.io.IOException;
import java.io.OutputStream;

/** Destino de escrita independente do formato e do provedor de armazenamento. */
public interface ArchiveDestination {
    void createDirectory(String relativePath) throws IOException;

    OutputStream createFile(String relativePath) throws IOException;

    /** Remove, quando possível, somente os itens criados pela operação atual. */
    void rollback();
}
