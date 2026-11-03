package com.fs20modconverterpro.gar;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Reconhece assinaturas superficiais; não interpreta índices nem conteúdo GAR. */
public final class GarInspector {
    public static final int HEADER_LIMIT = 128;
    private static final byte[] GIANTS_BANNER =
            "Copyright (C) GIANTS Software GmbH, All Rights Reserved".getBytes(StandardCharsets.US_ASCII);

    public Result inspect(String filename, long fileSize, InputStream input) throws IOException {
        if (input == null) throw new IOException("Não foi possível abrir o arquivo GAR.");
        byte[] header = new byte[HEADER_LIMIT];
        int size = 0;
        while (size < header.length) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Leitura do cabeçalho GAR cancelada.");
            }
            int read = input.read(header, size, header.length - size);
            if (read < 0) break;
            if (read == 0) continue;
            size += read;
        }
        byte[] prefix = new byte[size];
        System.arraycopy(header, 0, prefix, 0, size);
        return inspect(filename, fileSize, prefix);
    }

    public Result inspect(String filename, long fileSize, byte[] header) {
        String name = filename == null ? "" : filename;
        byte[] prefix = header == null ? new byte[0] : header;
        if (fileSize == 0 || fileSize > 0 && fileSize < 4 || prefix.length == 0) {
            return new Result(name, fileSize, "ARQUIVO INVÁLIDO", "Nenhum cabeçalho foi encontrado.",
                    "", false, false);
        }
        if (isZip(prefix)) {
            boolean garName = name.toLowerCase(Locale.ROOT).endsWith(".gar");
            return new Result(name, fileSize, garName ? "ZIP RENOMEADO PARA GAR" : "ARQUIVO ZIP",
                    garName ? "A assinatura PK identifica ZIP; mudar a extensão não o converte para GAR."
                            : "A assinatura PK identifica ZIP, não um arquivo GAR da GIANTS.",
                    "50 4B " + hex(prefix, 2, Math.min(8, prefix.length)), false, false);
        }
        if (startsWith(prefix, GIANTS_BANNER)) {
            return new Result(name, fileSize, "GAR IDENTIFICADO — CONTEÚDO NÃO VALIDADO",
                    "Foi reconhecido o banner de assinatura GIANTS. Sem especificação pública validada do índice/conteúdo, não é seguro listar, extrair ou reconstruir este arquivo.",
                    hex(prefix, 0, Math.min(GIANTS_BANNER.length, prefix.length)), true, false);
        }
        if (prefix.length < 4 || fileSize > 0 && fileSize < 4) {
            return new Result(name, fileSize, "ARQUIVO INVÁLIDO", "Cabeçalho truncado; são necessários ao menos quatro bytes.",
                    hex(prefix, 0, prefix.length), false, false);
        }
        String state = name.toLowerCase(Locale.ROOT).endsWith(".gar")
                ? "FORMATO GAR NÃO SUPORTADO" : "FORMATO NÃO RECONHECIDO";
        return new Result(name, fileSize, state,
                "Assinatura não reconhecida. O arquivo pode usar outra variante GAR ou estar protegido; o conteúdo não foi interpretado.",
                hex(prefix, 0, Math.min(24, prefix.length)), false, false);
    }

    private static boolean isZip(byte[] bytes) {
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') return false;
        return bytes[2] == 3 && bytes[3] == 4
                || bytes[2] == 5 && bytes[3] == 6
                || bytes[2] == 7 && bytes[3] == 8;
    }

    private static boolean startsWith(byte[] bytes, byte[] signature) {
        if (bytes.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) if (bytes[i] != signature[i]) return false;
        return true;
    }

    private static String hex(byte[] bytes, int start, int end) {
        StringBuilder result = new StringBuilder();
        for (int i = start; i < end; i++) {
            if (result.length() > 0) result.append(' ');
            result.append(String.format(Locale.ROOT, "%02X", bytes[i] & 0xff));
        }
        return result.toString();
    }

    public static final class Result {
        public final String filename;
        public final long fileSize;
        public final String state;
        public final String message;
        public final String signatureHex;
        public final boolean giantsBannerRecognized;
        public final boolean contentReadable;
        public final boolean canExtract;
        public final boolean canRebuild;
        public final String version = "Não identificada";

        private Result(String name, long size, String detectedState, String detail,
                       String signature, boolean banner, boolean readable) {
            filename = name;
            fileSize = size;
            state = detectedState;
            message = detail;
            signatureHex = signature;
            giantsBannerRecognized = banner;
            contentReadable = readable;
            canExtract = false;
            canRebuild = false;
        }

        public boolean isGarArchiveValidated() { return false; }

        public String toReport() {
            return "GERENCIADOR GAR PRO — RELATÓRIO DE IDENTIFICAÇÃO\n"
                    + "Arquivo: " + filename + "\n"
                    + "Tamanho: " + (fileSize < 0 ? "Não informado" : fileSize + " bytes") + "\n"
                    + "Estado: " + state + "\n"
                    + "Versão identificada: " + version + "\n"
                    + "Assinatura (hex): " + (signatureHex.isEmpty() ? "não disponível" : signatureHex) + "\n"
                    + "Conteúdo legível: não\nExtração validada: não\nReconstrução validada: não\n\n"
                    + message + "\n";
        }
    }
}
