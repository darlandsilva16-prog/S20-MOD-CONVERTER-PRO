package com.fs20modconverterpro.gar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Registro explícito de leitores de índice validados; inicia vazio. */
public final class GarIndexReaderRegistry {
    private final List<GarIndexReader<?>> readers = new ArrayList<>();

    public void register(GarIndexReader<?> reader) {
        if (reader == null) throw new IllegalArgumentException("O leitor GAR não pode ser nulo.");
        for (GarIndexReader<?> existing : readers) {
            if (existing.formatId().equals(reader.formatId())) {
                throw new IllegalArgumentException("Já existe um leitor registrado para esse formato GAR.");
            }
        }
        readers.add(reader);
    }

    public GarIndexReader<?> findReader(GarInspector.Result header) {
        if (header == null) return null;
        for (GarIndexReader<?> reader : readers) {
            if (reader.supports(header)) return reader;
        }
        return null;
    }

    public List<GarIndexReader<?>> readers() {
        return Collections.unmodifiableList(readers);
    }
}
