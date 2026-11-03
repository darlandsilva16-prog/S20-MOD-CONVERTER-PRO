package com.fs20modconverterpro.gar;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Inspeciona um prefixo limitado. Não lê índice, executa código ou altera o arquivo. */
public final class GarInspector {
    public static final int HEADER_LIMIT = 128;
    private static final byte[] GIANTS_BANNER =
            "Copyright (C) GIANTS Software GmbH, All Rights Reserved".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] GAR_SIGNATURE = {'G', 'A', 'R', ' '};
    private static final int[] OBSERVED_FIELD_BITS = {16, 16, 32};

    public Result inspect(String filename, long fileSize, InputStream input) throws IOException {
        if (input == null) throw new IOException("Não foi possível abrir o arquivo GAR.");
        int limit = HEADER_LIMIT;
        if (fileSize >= 0 && fileSize < limit) limit = (int) fileSize;
        byte[] header = new byte[limit];
        int size = 0;
        while (size < header.length) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Leitura cancelada.");
            int read = input.read(header, size, header.length - size);
            if (read < 0) break;
            if (read == 0) {
                int one = input.read();
                if (one < 0) break;
                header[size++] = (byte) one;
            } else size += read;
        }
        byte[] prefix = new byte[size];
        System.arraycopy(header, 0, prefix, 0, size);
        return inspect(filename, fileSize, prefix);
    }

    public Result inspect(String filename, long fileSize, byte[] header) {
        String name = filename == null ? "" : filename;
        byte[] supplied = header == null ? new byte[0] : header;
        int length = Math.min(supplied.length, HEADER_LIMIT);
        if (fileSize >= 0) length = (int) Math.min(length, fileSize);
        byte[] prefix = new byte[length];
        System.arraycopy(supplied, 0, prefix, 0, length);
        int bannerOffset = find(prefix, GIANTS_BANNER);
        boolean banner = bannerOffset >= 0;
        if (fileSize == 0 || (fileSize > 0 && fileSize < 4) || prefix.length == 0) {
            return result(name, fileSize, GarAnalysisState.FORMATO_NAO_SUPORTADO,
                    "Prefixo vazio ou tamanho inconsistente.",
                    "", bannerOffset, -1, Collections.<HeaderField>emptyList());
        }
        if (isZip(prefix)) {
            return result(name, fileSize, GarAnalysisState.FORMATO_NAO_SUPORTADO,
                    "A assinatura PK identifica ZIP; alterar a extensão não o converte em GAR.",
                    "", bannerOffset, -1,
                    Collections.<HeaderField>emptyList());
        }
        int signatureOffset = find(prefix, GAR_SIGNATURE);
        if (signatureOffset < 0) {
            GarAnalysisState state = banner ? GarAnalysisState.ESTRUTURA_NAO_VALIDADA
                    : GarAnalysisState.FORMATO_NAO_SUPORTADO;
            return result(name, fileSize, state,
                    "Assinatura GAR (GAR seguido de espaço) não encontrada nos primeiros " + HEADER_LIMIT + " bytes.",
                    "", bannerOffset, -1,
                    Collections.<HeaderField>emptyList());
        }
        List<HeaderField> fields = readObservedFields(prefix, signatureOffset);
        boolean complete = fields.size() == OBSERVED_FIELD_BITS.length;
        GarAnalysisState state = complete && banner
                ? GarAnalysisState.CABECALHO_IDENTIFICADO
                : GarAnalysisState.ESTRUTURA_NAO_VALIDADA;
        String message = "Banner GIANTS: " + (banner ? "posição " + bannerOffset : "não encontrado")
                + "; assinatura GAR: posição " + signatureOffset + ". Os valores mostrados são observações "
                + "binárias; nenhum significado de campo é afirmado.";
        return result(name, fileSize, state, message, hex(prefix, signatureOffset,
                Math.min(prefix.length, signatureOffset + GAR_SIGNATURE.length)), bannerOffset,
                signatureOffset, fields);
    }

    private Result result(String name, long size, GarAnalysisState state, String message, String signatureHex,
                          int bannerOffset, int signatureOffset, List<HeaderField> fields) {
        return new Result(name, size, state, message, signatureHex, bannerOffset, signatureOffset, fields);
    }

    private static List<HeaderField> readObservedFields(byte[] bytes, int signatureOffset) {
        int cursor = signatureOffset + GAR_SIGNATURE.length;
        List<HeaderField> fields = new ArrayList<>();
        for (int index = 0; index < OBSERVED_FIELD_BITS.length; index++) {
            int byteCount = OBSERVED_FIELD_BITS[index] / 8;
            if (cursor > bytes.length - byteCount) break;
            long value = 0;
            for (int j = 0; j < byteCount; j++) value |= ((long) bytes[cursor + j] & 0xff) << (8 * j);
            fields.add(new HeaderField(index + 1, OBSERVED_FIELD_BITS[index], value,
                    hex(bytes, cursor, cursor + byteCount)));
            cursor += byteCount;
        }
        return fields;
    }

    private static int find(byte[] bytes, byte[] expected) {
        for (int offset = 0; offset <= bytes.length - expected.length; offset++) {
            boolean match = true;
            for (int i = 0; i < expected.length; i++) if (bytes[offset + i] != expected[i]) { match = false; break; }
            if (match) return offset;
        }
        return -1;
    }

    private static boolean isZip(byte[] bytes) {
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') return false;
        return (bytes[2] == 3 && bytes[3] == 4) || (bytes[2] == 5 && bytes[3] == 6)
                || (bytes[2] == 7 && bytes[3] == 8);
    }

    private static String hex(byte[] bytes, int start, int end) {
        StringBuilder value = new StringBuilder();
        for (int i = start; i < end; i++) {
            if (value.length() > 0) value.append(' ');
            value.append(String.format(Locale.ROOT, "%02X", bytes[i] & 0xff));
        }
        return value.toString();
    }

    public static final class HeaderField {
        public final int index;
        public final int widthBits;
        public final long observedValue;
        public final String rawHex;
        private HeaderField(int index, int widthBits, long value, String rawHex) {
            this.index = index; this.widthBits = widthBits; this.observedValue = value; this.rawHex = rawHex;
        }
        public String label() { return "Valor observado " + index + " (significado desconhecido)"; }
    }

    public static final class Result {
        public final String filename, state, message, signatureHex;
        public final GarAnalysisState stateCode;
        public final long fileSize;
        public final int giantsBannerOffset, garSignatureOffset;
        public final List<HeaderField> fields;
        public final boolean contentReadable = false, canExtract = false, canRebuild = false;
        public final String version = "Não confirmada";
        private Result(String filename, long fileSize, GarAnalysisState state, String message, String signatureHex,
                       int bannerOffset, int signatureOffset, List<HeaderField> fields) {
            this.filename = filename; this.fileSize = fileSize; this.stateCode = state;
            this.state = state.label(); this.message = message;
            this.signatureHex = signatureHex; this.giantsBannerOffset = bannerOffset;
            this.garSignatureOffset = signatureOffset;
            this.fields = Collections.unmodifiableList(new ArrayList<>(fields));
        }
        public boolean isGarArchiveValidated() { return false; }
        public String toReport() {
            StringBuilder out = new StringBuilder("ANÁLISE GAR — RELATÓRIO SOMENTE PARA LEITURA\n")
                    .append("Arquivo: ").append(filename).append('\n')
                    .append("Tamanho total: ").append(fileSize < 0 ? "Não informado" : fileSize + " bytes").append('\n')
                    .append("Estado: ").append(state).append(" (").append(stateCode.name()).append(")\n")
                    .append("Banner GIANTS: ").append(giantsBannerOffset < 0 ? "não encontrado" : "posição " + giantsBannerOffset).append('\n')
                    .append("Assinatura GAR: ").append(garSignatureOffset < 0 ? "não encontrada" : "posição " + garSignatureOffset).append('\n')
                    .append("Bytes brutos da assinatura: ").append(signatureHex.isEmpty() ? "não disponíveis" : signatureHex).append('\n')
                    .append("Versão: não confirmada\nValores observados (sem significado atribuído):\n");
            for (HeaderField field : fields) out.append("- ").append(field.label()).append(": ")
                    .append(field.observedValue).append(" (uint").append(field.widthBits).append(" little-endian; bytes ")
                    .append(field.rawHex).append(")\n");
            if (fields.size() < OBSERVED_FIELD_BITS.length) out.append("- Campos observados incompletos: ")
                    .append(OBSERVED_FIELD_BITS.length - fields.size()).append(" valor(es) não puderam ser lidos integralmente.\n");
            return out.append("\nÍndice GAR: leitura ainda não disponível.\n")
                    .append("Estado do índice: ESTRUTURA NÃO VALIDADA\n")
                    .append("Conteúdo validado: não\nPesquisa/extração/descriptografia/reconstrução: desativadas\n\n")
                    .append(message).append('\n').toString();
        }
    }
}
