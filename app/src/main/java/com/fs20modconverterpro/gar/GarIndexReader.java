package com.fs20modconverterpro.gar;

import java.io.IOException;
import java.io.InputStream;

/**
 * Contrato para leitores futuros do índice GAR.
 * Registre uma implementação somente depois de validar sua estrutura com especificação
 * confiável ou amostra autorizada. O tipo de retorno permanece genérico para não inventar
 * agora um modelo de entrada ou campos do índice.
 */
public interface GarIndexReader<T> {
    String formatId();

    boolean supports(GarInspector.Result header);

    T readIndex(InputStream input, long fileSize, GarInspector.Result header) throws IOException;
}
