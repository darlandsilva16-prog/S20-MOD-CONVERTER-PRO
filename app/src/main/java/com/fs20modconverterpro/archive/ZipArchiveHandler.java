package com.fs20modconverterpro.archive;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Leitor e extrator ZIP com validação de caminhos e limites contra arquivos abusivos. */
public final class ZipArchiveHandler implements ArchiveHandler {
    static final int MAX_ENTRIES = 20_000;
    static final int MAX_LISTED_ENTRIES = 500;
    static final int MAX_PATH_LENGTH = 4_096;
    static final int MAX_SEGMENT_LENGTH = 255;
    static final long MAX_ENTRY_BYTES = 256L * 1024L * 1024L;
    static final long MAX_TOTAL_BYTES = 1_024L * 1024L * 1024L;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long PROGRESS_INTERVAL_BYTES = 1024L * 1024L;

    @Override
    public boolean supports(String displayName, String mimeType) {
        String name = displayName == null ? "" : displayName.toLowerCase(Locale.ROOT);
        String mime = ModArchiveManager.normalizedMimeType(mimeType);
        return name.endsWith(".zip")
                || "application/zip".equals(mime)
                || "application/x-zip-compressed".equals(mime);
    }

    @Override
    public ArchiveInspection inspect(InputStream input, ArchiveProgressListener progressListener)
            throws IOException {
        List<ArchiveEntryInfo> entries = new ArrayList<>();
        PathRegistry paths = new PathRegistry();
        long[] totalBytes = {0};
        int entryCount = 0;
        int unsafeCount = 0;

        try (ZipInputStream zip = openZip(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                checkInterrupted();
                entryCount++;
                if (entryCount > MAX_ENTRIES) {
                    throw new IOException("O ZIP excede o limite de 20.000 itens.");
                }

                String path = null;
                String validationError = null;
                try {
                    path = paths.register(entry.getName(), entry.isDirectory());
                } catch (UnsafeArchiveEntryException exception) {
                    validationError = exception.getMessage();
                    unsafeCount++;
                }

                long entryBytes = readEntry(zip, null, entry, totalBytes, entryCount,
                        progressListener);
                if (entry.isDirectory() && entryBytes != 0 && validationError == null) {
                    validationError = "Diretório ZIP contém dados de arquivo.";
                    unsafeCount++;
                }

                if (entries.size() < MAX_LISTED_ENTRIES) {
                    String displayName = shorten(path == null ? entry.getName() : path);
                    entries.add(new ArchiveEntryInfo(
                            displayName,
                            entryBytes,
                            entry.isDirectory(),
                            validationError == null));
                }
                zip.closeEntry();
            }
        }

        if (entryCount == 0) {
            throw new IOException("O ZIP está vazio ou não contém itens válidos.");
        }
        return new ArchiveInspection(
                entries,
                entryCount,
                unsafeCount,
                totalBytes[0],
                entryCount > MAX_LISTED_ENTRIES);
    }

    @Override
    public ArchiveExtractionResult extract(
            InputStream input,
            ArchiveDestination destination,
            ArchiveProgressListener progressListener) throws IOException {
        if (destination == null) {
            throw new IllegalArgumentException("A pasta de destino não foi informada.");
        }

        PathRegistry paths = new PathRegistry();
        long[] totalBytes = {0};
        int entryCount = 0;
        try (ZipInputStream zip = openZip(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                checkInterrupted();
                entryCount++;
                if (entryCount > MAX_ENTRIES) {
                    throw new IOException("O ZIP excede o limite de 20.000 itens.");
                }

                String path = paths.register(entry.getName(), entry.isDirectory());
                if (entry.isDirectory()) {
                    destination.createDirectory(path);
                    long directoryBytes = readEntry(
                            zip, null, entry, totalBytes, entryCount, progressListener);
                    if (directoryBytes != 0) {
                        throw new IOException("Diretório ZIP contém dados inesperados.");
                    }
                } else {
                    try (OutputStream output = destination.createFile(path)) {
                        if (output == null) {
                            throw new IOException("Não foi possível criar um arquivo na pasta escolhida.");
                        }
                        readEntry(zip, output, entry, totalBytes, entryCount, progressListener);
                    }
                }
                zip.closeEntry();
            }
            if (entryCount == 0) {
                throw new IOException("O ZIP está vazio ou não contém itens válidos.");
            }
        } catch (IOException | RuntimeException exception) {
            destination.rollback();
            if (exception instanceof IOException) {
                throw (IOException) exception;
            }
            throw new IOException("Falha ao gravar os itens na pasta escolhida.", exception);
        }
        return new ArchiveExtractionResult(entryCount, totalBytes[0]);
    }

    private static ZipInputStream openZip(InputStream input) throws IOException {
        if (input == null) {
            throw new IOException("Não foi possível abrir o arquivo selecionado.");
        }
        PushbackInputStream source = new PushbackInputStream(
                new BufferedInputStream(input), 4);
        byte[] signature = new byte[4];
        int offset = 0;
        while (offset < signature.length) {
            int read = source.read(signature, offset, signature.length - offset);
            if (read < 0) {
                source.close();
                throw new IOException("O arquivo selecionado não é um ZIP válido.");
            }
            offset += read;
        }

        boolean localFileHeader = signature[0] == 'P' && signature[1] == 'K'
                && signature[2] == 3 && signature[3] == 4;
        boolean emptyArchiveDirectory = signature[0] == 'P' && signature[1] == 'K'
                && signature[2] == 5 && signature[3] == 6;
        if (!localFileHeader && !emptyArchiveDirectory) {
            source.close();
            throw new IOException("O arquivo selecionado não é um ZIP compatível.");
        }
        source.unread(signature);
        return new ZipInputStream(source);
    }

    private static long readEntry(
            ZipInputStream zip,
            OutputStream output,
            ZipEntry entry,
            long[] totalBytes,
            int entryCount,
            ArchiveProgressListener progressListener) throws IOException {
        long declaredSize = entry.getSize();
        if (declaredSize > MAX_ENTRY_BYTES) {
            throw new IOException("Um item do ZIP excede o limite de 256 MB.");
        }

        byte[] buffer = new byte[BUFFER_SIZE];
        long entryBytes = 0;
        long lastProgressBytes = totalBytes[0];
        int read;
        while ((read = zip.read(buffer)) != -1) {
            checkInterrupted();
            if (read == 0) {
                continue;
            }
            if (entryBytes > MAX_ENTRY_BYTES - read) {
                throw new IOException("Um item do ZIP excede o limite de 256 MB.");
            }
            if (totalBytes[0] > MAX_TOTAL_BYTES - read) {
                throw new IOException("O conteúdo do ZIP excede o limite total de 1 GB.");
            }
            entryBytes += read;
            totalBytes[0] += read;
            if (output != null) {
                output.write(buffer, 0, read);
            }
            if (progressListener != null
                    && totalBytes[0] - lastProgressBytes >= PROGRESS_INTERVAL_BYTES) {
                progressListener.onProgress(entryCount, totalBytes[0], entry.getName());
                lastProgressBytes = totalBytes[0];
            }
        }
        if (progressListener != null) {
            progressListener.onProgress(entryCount, totalBytes[0], entry.getName());
        }
        if (declaredSize >= 0 && declaredSize != entryBytes) {
            throw new IOException("O tamanho declarado de um item ZIP não corresponde ao conteúdo.");
        }
        return entryBytes;
    }

    private static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("A operação foi cancelada.");
        }
    }

    private static String shorten(String value) {
        if (value == null || value.length() <= 512) {
            return value == null ? "(nome inválido)" : value;
        }
        return value.substring(0, 509) + "...";
    }

    private static final class PathRegistry {
        private final Map<String, Boolean> types = new HashMap<>();

        String register(String entryName, boolean directory) throws UnsafeArchiveEntryException {
            if (entryName == null || entryName.isEmpty() || entryName.length() > MAX_PATH_LENGTH) {
                throw new UnsafeArchiveEntryException("Nome de caminho vazio ou longo demais.");
            }
            if (entryName.startsWith("/") || entryName.startsWith("\\")
                    || entryName.indexOf('\\') >= 0 || entryName.indexOf('\0') >= 0) {
                throw new UnsafeArchiveEntryException("Caminho absoluto ou com separador inválido.");
            }

            String path = entryName;
            if (directory && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            if (path.isEmpty()) {
                throw new UnsafeArchiveEntryException("O caminho da entrada está vazio.");
            }

            String[] segments = path.split("/", -1);
            for (int index = 0; index < segments.length; index++) {
                String segment = segments[index];
                if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                    throw new UnsafeArchiveEntryException("O caminho contém um segmento inseguro.");
                }
                if (segment.length() > MAX_SEGMENT_LENGTH) {
                    throw new UnsafeArchiveEntryException("Um nome de pasta ou arquivo é longo demais.");
                }
                if (index == 0 && segment.length() >= 2
                        && Character.isLetter(segment.charAt(0)) && segment.charAt(1) == ':') {
                    throw new UnsafeArchiveEntryException("Caminho de unidade absoluta não permitido.");
                }
                for (int character = 0; character < segment.length(); character++) {
                    if (Character.isISOControl(segment.charAt(character))) {
                        throw new UnsafeArchiveEntryException("O nome contém caracteres de controle.");
                    }
                }
            }

            StringBuilder normalized = new StringBuilder(path.length());
            StringBuilder key = new StringBuilder(path.length());
            for (int index = 0; index < segments.length; index++) {
                if (index > 0) {
                    normalized.append('/');
                    key.append('/');
                }
                normalized.append(segments[index]);
                key.append(segments[index]);
            }

            String[] normalizedSegments = normalized.toString().split("/");
            StringBuilder parent = new StringBuilder();
            for (int index = 0; index < normalizedSegments.length - 1; index++) {
                if (index > 0) {
                    parent.append('/');
                }
                parent.append(normalizedSegments[index]);
                String parentKey = parent.toString();
                if (Boolean.FALSE.equals(types.get(parentKey))) {
                    throw new UnsafeArchiveEntryException("Um arquivo do ZIP também é usado como pasta.");
                }
            }

            String normalizedPath = normalized.toString();
            String pathKey = key.toString();
            Boolean existing = types.get(pathKey);
            if (directory) {
                if (Boolean.FALSE.equals(existing)) {
                    throw new UnsafeArchiveEntryException("O mesmo caminho aparece como arquivo e pasta.");
                }
            } else if (existing != null) {
                throw new UnsafeArchiveEntryException("O ZIP contém caminhos repetidos ou conflitantes.");
            }

            parent.setLength(0);
            for (int index = 0; index < normalizedSegments.length; index++) {
                if (index > 0) {
                    parent.append('/');
                }
                parent.append(normalizedSegments[index]);
                String currentKey = parent.toString();
                boolean isLast = index == normalizedSegments.length - 1;
                if (!isLast || directory) {
                    types.putIfAbsent(currentKey, Boolean.TRUE);
                } else {
                    types.put(currentKey, Boolean.FALSE);
                }
            }
            return normalizedPath;
        }
    }

    private static final class UnsafeArchiveEntryException extends IOException {
        UnsafeArchiveEntryException(String message) {
            super(message);
        }
    }
}
