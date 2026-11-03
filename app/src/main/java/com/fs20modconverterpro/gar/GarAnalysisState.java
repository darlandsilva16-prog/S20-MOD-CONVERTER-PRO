package com.fs20modconverterpro.gar;

/** Estados de identificação observacional; nenhum deles confirma o índice GAR. */
public enum GarAnalysisState {
    CABECALHO_IDENTIFICADO("CABEÇALHO IDENTIFICADO"),
    ESTRUTURA_NAO_VALIDADA("ESTRUTURA NÃO VALIDADA"),
    FORMATO_NAO_SUPORTADO("FORMATO NÃO SUPORTADO");

    private final String label;

    GarAnalysisState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
