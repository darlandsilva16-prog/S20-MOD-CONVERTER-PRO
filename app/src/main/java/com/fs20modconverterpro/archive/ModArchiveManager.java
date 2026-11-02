package com.fs20modconverterpro.archive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Localiza o leitor compatível; novos formatos podem ser registrados sem alterar a interface. */
public final class ModArchiveManager {
    private final List<ArchiveHandler> handlers = new ArrayList<>();

    public ModArchiveManager() {
        registerHandler(new ZipArchiveHandler());
    }

    public void registerHandler(ArchiveHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("O leitor do arquivo não pode ser nulo.");
        }
        handlers.add(handler);
    }

    public ArchiveHandler findHandler(String displayName, String mimeType) {
        for (ArchiveHandler handler : handlers) {
            if (handler.supports(displayName, mimeType)) {
                return handler;
            }
        }
        return null;
    }

    public List<ArchiveHandler> getHandlers() {
        return Collections.unmodifiableList(handlers);
    }

    static String normalizedMimeType(String mimeType) {
        return mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
    }
}
