package com.fs20modconverterpro.gar.synthetic;

import com.fs20modconverterpro.gar.GarAnalysisState;
import com.fs20modconverterpro.gar.GarInspector;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class GarInspectorSyntheticTest {
    private static final long SAMPLE_SIZE = 36095240L;
    private final GarInspector inspector = new GarInspector();

    @Test public void cabecalhoSinteticoMostraBytesLargurasEValoresSemSemantica() {
        byte[] header = observedHeader();
        byte[] original = header.clone();
        GarInspector.Result result = inspector.inspect("dataS.gar", SAMPLE_SIZE, header);
        assertEquals(GarAnalysisState.CABECALHO_IDENTIFICADO, result.stateCode);
        assertEquals(14, result.giantsBannerOffset);
        assertEquals(96, result.garSignatureOffset);
        assertEquals(SAMPLE_SIZE, result.fileSize);
        assertEquals("47 41 52 20", result.signatureHex);
        assertEquals(3, result.fields.size());
        assertObservedField(result.fields.get(0), 1, 16, 4, "04 00");
        assertObservedField(result.fields.get(1), 2, 16, 5, "05 00");
        assertObservedField(result.fields.get(2), 3, 32, 2212, "A4 08 00 00");
        String report = result.toReport();
        assertTrue(report.contains("Tamanho total: 36095240 bytes"));
        assertTrue(report.contains("posição 14"));
        assertTrue(report.contains("posição 96"));
        assertFalse(report.toLowerCase().contains("2212 arquivos"));
        assertFalse(result.isGarArchiveValidated());
        assertFalse(result.contentReadable);
        assertFalse(result.canExtract);
        assertFalse(result.canRebuild);
        assertArrayEquals("A análise não altera o cabeçalho", original, header);
    }

    @Test public void arquivoTruncadoAntesDaAssinaturaPermaneceNaoValidado() {
        byte[] truncated = new byte[80];
        byte[] banner = giantsBanner();
        System.arraycopy(banner, 0, truncated, 14, banner.length);
        GarInspector.Result result = inspector.inspect("dataS.gar", 80, truncated);
        assertEquals(14, result.giantsBannerOffset);
        assertEquals(-1, result.garSignatureOffset);
        assertEquals(GarAnalysisState.ESTRUTURA_NAO_VALIDADA, result.stateCode);
        assertFalse(result.canExtract);
    }

    @Test public void assinaturaAusenteNaoEInferidaPeloNomeNemPeloBanner() {
        byte[] header = new byte[128];
        byte[] banner = giantsBanner();
        System.arraycopy(banner, 0, header, 14, banner.length);
        GarInspector.Result result = inspector.inspect("dataS.gar", 1000, header);
        assertEquals(14, result.giantsBannerOffset);
        assertEquals(-1, result.garSignatureOffset);
        assertTrue(result.message.contains("GAR seguido de espaço"));
        assertTrue(result.fields.isEmpty());
    }

    @Test public void assinaturaAusenteEmCabecalhoDesconhecidoPermaneceBloqueada() {
        GarInspector.Result result = inspector.inspect("desconhecido.gar", 6,
                new byte[]{0x13, 0x37, 0x02, 0x01, 0x55, 0x66});
        assertEquals(GarAnalysisState.FORMATO_NAO_SUPORTADO, result.stateCode);
        assertEquals(-1, result.garSignatureOffset);
        assertFalse(result.canExtract);
        assertFalse(result.canRebuild);
    }

    @Test public void camposIncompletosNaoSaoLidosComoInteirosParciais() {
        byte[] partial = observedHeader();
        GarInspector.Result result = inspector.inspect("parcial.gar", 101, copyPrefix(partial, 101));
        assertEquals(96, result.garSignatureOffset);
        assertTrue(result.fields.isEmpty());
        assertTrue(result.toReport().contains("Campos observados incompletos: 3"));
        GarInspector.Result twoFields = inspector.inspect("parcial.gar", 106, copyPrefix(partial, 106));
        assertEquals(2, twoFields.fields.size());
        assertEquals(4L, twoFields.fields.get(0).observedValue);
        assertEquals(5L, twoFields.fields.get(1).observedValue);
        assertTrue(twoFields.toReport().contains("Campos observados incompletos: 1"));
    }

    @Test public void valoresNosLimitesSaoDecodificadosPelasLargurasObservadas() {
        byte[] bytes = observedHeader();
        for (int i = 100; i < 108; i++) bytes[i] = (byte) 0xff;
        GarInspector.Result result = inspector.inspect("limites.gar", SAMPLE_SIZE, bytes);
        assertEquals(65535L, result.fields.get(0).observedValue);
        assertEquals(16, result.fields.get(0).widthBits);
        assertEquals(65535L, result.fields.get(1).observedValue);
        assertEquals(4294967295L, result.fields.get(2).observedValue);
        assertEquals(32, result.fields.get(2).widthBits);
        assertFalse(result.canExtract);
        assertFalse(result.canRebuild);
    }

    @Test public void tamanhoDeclaradoLimitaOsBytesDisponiveisParaAnalise() {
        GarInspector.Result result = inspector.inspect("curto.gar", 98, observedHeader());
        assertEquals(-1, result.garSignatureOffset);
    }

    @Test public void cabecalhoMaiorQueLimiteNaoEConsumidoIntegralmente() throws Exception {
        byte[] large = new byte[512];
        System.arraycopy(observedHeader(), 0, large, 0, 128);
        ByteArrayInputStream input = new ByteArrayInputStream(large);
        GarInspector.Result result = inspector.inspect("large.gar", large.length, input);
        assertEquals(384, input.available());
        assertEquals(96, result.garSignatureOffset);
    }

    @Test public void arquivoVazioETamanhoImpossivelSaoRejeitados() {
        assertEquals(GarAnalysisState.FORMATO_NAO_SUPORTADO,
                inspector.inspect("vazio.gar", 0, new byte[0]).stateCode);
        assertEquals(GarAnalysisState.FORMATO_NAO_SUPORTADO,
                inspector.inspect("curto.gar", 2, new byte[]{1, 2}).stateCode);
    }

    @Test public void zipRenomeadoNaoEInterpretadoComoGar() {
        GarInspector.Result result = inspector.inspect("mod.gar", 8, new byte[]{'P', 'K', 3, 4, 0, 0, 0, 0});
        assertEquals(GarAnalysisState.FORMATO_NAO_SUPORTADO, result.stateCode);
        assertFalse(result.canExtract);
        assertFalse(result.canRebuild);
        assertTrue(result.toReport().contains("Índice GAR: leitura ainda não disponível."));
    }

    @Test public void operacoesPermanecemDesativadasEOriginalIntacto() throws Exception {
        byte[] original = observedHeader();
        byte[] before = original.clone();
        GarInspector.Result result = inspector.inspect("dataS.gar", SAMPLE_SIZE, new ByteArrayInputStream(original));
        assertFalse(result.canExtract);
        assertFalse(result.canRebuild);
        assertArrayEquals(before, original);
    }

    @Test public void leituraRespeitaCancelamento() {
        Thread.currentThread().interrupt();
        try {
            inspector.inspect("cancel.gar", 128, new ByteArrayInputStream(observedHeader()));
            fail("a leitura interrompida não foi cancelada");
        } catch (InterruptedIOException expected) {
            assertTrue(expected.getMessage().contains("cancelada"));
        } catch (IOException unexpected) {
            fail("erro inesperado: " + unexpected.getMessage());
        } finally { Thread.interrupted(); }
    }

    @Test public void erroDeLeituraEPropagado() {
        InputStream failing = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("falha fixture"); }
            @Override public int read(byte[] buffer, int offset, int length) throws IOException { throw new IOException("falha fixture"); }
        };
        try { inspector.inspect("x.gar", 100, failing); fail("falha ignorada"); }
        catch (IOException expected) { assertEquals("falha fixture", expected.getMessage()); }
    }

    private static void assertObservedField(GarInspector.HeaderField field, int index, int width,
                                           long value, String rawHex) {
        assertEquals(index, field.index);
        assertEquals(width, field.widthBits);
        assertEquals(value, field.observedValue);
        assertEquals(rawHex, field.rawHex);
        assertTrue(field.label().contains("significado desconhecido"));
    }

    private static byte[] observedHeader() {
        byte[] bytes = new byte[128];
        byte[] banner = giantsBanner();
        System.arraycopy(banner, 0, bytes, 14, banner.length);
        bytes[96] = 'G'; bytes[97] = 'A'; bytes[98] = 'R'; bytes[99] = ' ';
        bytes[100] = 0x04; bytes[101] = 0x00;
        bytes[102] = 0x05; bytes[103] = 0x00;
        bytes[104] = (byte) 0xA4; bytes[105] = 0x08; bytes[106] = 0x00; bytes[107] = 0x00;
        return bytes;
    }

    private static byte[] copyPrefix(byte[] source, int length) {
        byte[] prefix = new byte[length];
        System.arraycopy(source, 0, prefix, 0, length);
        return prefix;
    }

    private static byte[] giantsBanner() {
        return "Copyright (C) GIANTS Software GmbH, All Rights Reserved".getBytes(StandardCharsets.US_ASCII);
    }
}
