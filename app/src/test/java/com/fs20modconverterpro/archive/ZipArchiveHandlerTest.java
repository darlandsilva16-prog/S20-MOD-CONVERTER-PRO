package com.fs20modconverterpro.archive;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ZipArchiveHandlerTest {
    private final ZipArchiveHandler handler = new ZipArchiveHandler();

    @Test
    public void inspecionaConteudoETamanhoRealDasEntradas() throws Exception {
        byte[] archive = zip(
                new String[]{"mod/data/", "mod/data/info.txt"},
                new byte[][]{new byte[0], "conteudo do mod".getBytes(StandardCharsets.UTF_8)});

        ArchiveInspection inspection = handler.inspect(new ByteArrayInputStream(archive), null);

        assertEquals(2, inspection.getTotalEntries());
        assertEquals(15, inspection.getTotalUncompressedBytes());
        assertTrue(inspection.canExtract());
        assertEquals("mod/data/info.txt", inspection.getEntries().get(1).getName());
        assertEquals(15, inspection.getEntries().get(1).getUncompressedSize());
    }

    @Test
    public void extraiMantendoPastasENomesOriginais() throws Exception {
        byte[] contents = "dados de teste".getBytes(StandardCharsets.UTF_8);
        byte[] archive = zip(
                new String[]{"pasta/", "pasta/mod.xml"},
                new byte[][]{new byte[0], contents});
        MemoryDestination destination = new MemoryDestination();

        ArchiveExtractionResult result = handler.extract(
                new ByteArrayInputStream(archive), destination, null);

        assertTrue(destination.directories.contains("pasta"));
        assertArrayEquals(contents, destination.files.get("pasta/mod.xml").toByteArray());
        assertEquals(2, result.getExtractedEntries());
        assertEquals(contents.length, result.getExtractedBytes());
        assertFalse(destination.rolledBack);
    }

    @Test
    public void bloqueiaZipSlipERemoveArquivosCriadosNaTentativa() throws Exception {
        byte[] archive = zip(
                new String[]{"mod/seguro.txt", "../../fora.txt"},
                new byte[][]{"seguro".getBytes(StandardCharsets.UTF_8), "ataque".getBytes(StandardCharsets.UTF_8)});

        ArchiveInspection inspection = handler.inspect(new ByteArrayInputStream(archive), null);
        assertFalse(inspection.canExtract());
        assertEquals(1, inspection.getUnsafeEntries());
        assertFalse(inspection.getEntries().get(1).isSafe());

        MemoryDestination destination = new MemoryDestination();
        try {
            handler.extract(new ByteArrayInputStream(archive), destination, null);
            fail("A extração deveria rejeitar o caminho de travessia.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("segmento inseguro"));
        }
        assertTrue(destination.rolledBack);
        assertTrue(destination.files.isEmpty());
        assertTrue(destination.directories.isEmpty());
    }

    @Test
    public void rejeitaEntradaComTamanhoDeclaradoAcimaDoLimite() throws Exception {
        byte[] archive = zipHeaderWithDeclaredSize(ZipArchiveHandler.MAX_ENTRY_BYTES + 1);
        try {
            handler.inspect(new ByteArrayInputStream(archive), null);
            fail("A inspeção deveria rejeitar um item acima do limite.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("256 MB"));
        }
    }

    @Test
    public void preservaDiferencasDeMaiusculasNosCaminhos() throws Exception {
        byte[] archive = zip(
                new String[]{"Mod/Arquivo.xml", "mod/arquivo.xml"},
                new byte[][]{"um".getBytes(StandardCharsets.UTF_8), "dois".getBytes(StandardCharsets.UTF_8)});
        MemoryDestination destination = new MemoryDestination();

        ArchiveExtractionResult result = handler.extract(
                new ByteArrayInputStream(archive), destination, null);

        assertEquals(2, result.getExtractedEntries());
        assertArrayEquals("um".getBytes(StandardCharsets.UTF_8),
                destination.files.get("Mod/Arquivo.xml").toByteArray());
        assertArrayEquals("dois".getBytes(StandardCharsets.UTF_8),
                destination.files.get("mod/arquivo.xml").toByteArray());
    }

    @Test
    public void gerenciadorNaoDeclaraGarComoSuportadoAntesDaImplementacao() {
        ModArchiveManager manager = new ModArchiveManager();

        assertTrue(manager.findHandler("mod.zip", "application/zip") != null);
        assertTrue(manager.findHandler("mod.gar", "application/octet-stream") == null);
    }

    @Test
    public void rejeitaArquivoQueNaoPossuiAssinaturaZip() throws Exception {
        try {
            handler.inspect(new ByteArrayInputStream("nao e zip".getBytes(StandardCharsets.UTF_8)), null);
            fail("A inspeção deveria rejeitar um arquivo que não é ZIP.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("não é um ZIP"));
        }
    }

    @Test
    public void reconheceSomenteExtensaoOuMimeZip() {
        assertTrue(handler.supports("MOD.ZIP", "application/octet-stream"));
        assertTrue(handler.supports("arquivo-sem-extensao", "application/zip"));
        assertFalse(handler.supports("mod.gar", "application/octet-stream"));
    }

    private static byte[] zip(String[] names, byte[][] contents) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (int index = 0; index < names.length; index++) {
                zip.putNextEntry(new ZipEntry(names[index]));
                zip.write(contents[index]);
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] zipHeaderWithDeclaredSize(long size) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        writeLittleEndian(bytes, 0x04034b50L, 4);
        writeLittleEndian(bytes, 20, 2);
        writeLittleEndian(bytes, 0, 2);
        writeLittleEndian(bytes, 0, 2);
        writeLittleEndian(bytes, 0, 2);
        writeLittleEndian(bytes, 0, 2);
        writeLittleEndian(bytes, 0, 4);
        writeLittleEndian(bytes, 0, 4);
        writeLittleEndian(bytes, size, 4);
        byte[] name = "large.bin".getBytes(StandardCharsets.UTF_8);
        writeLittleEndian(bytes, name.length, 2);
        writeLittleEndian(bytes, 0, 2);
        bytes.write(name);
        return bytes.toByteArray();
    }

    private static void writeLittleEndian(ByteArrayOutputStream output, long value, int byteCount) {
        for (int index = 0; index < byteCount; index++) {
            output.write((int) (value >>> (index * 8)) & 0xff);
        }
    }

    private static final class MemoryDestination implements ArchiveDestination {
        final Map<String, ByteArrayOutputStream> files = new HashMap<>();
        final Set<String> directories = new HashSet<>();
        boolean rolledBack;

        @Override
        public void createDirectory(String relativePath) {
            directories.add(relativePath);
        }

        @Override
        public OutputStream createFile(String relativePath) throws IOException {
            if (files.containsKey(relativePath)) {
                throw new IOException("Arquivo duplicado no destino.");
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            files.put(relativePath, output);
            return output;
        }

        @Override
        public void rollback() {
            rolledBack = true;
            files.clear();
            directories.clear();
        }
    }
}
