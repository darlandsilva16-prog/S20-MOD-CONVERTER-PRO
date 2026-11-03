package com.fs20modconverterpro.gar;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class GarInspectorTest {
    private final GarInspector inspector = new GarInspector();

    @Test public void identificaBannerGiantsSemDeclararConteudoLegivel() {
        byte[] original = "Copyright (C) GIANTS Software GmbH, All Rights Reserved\0opaque".getBytes(StandardCharsets.US_ASCII);
        byte[] before = original.clone();
        GarInspector.Result result = inspector.inspect("dataS.gar", original.length, original);
        assertEquals("GAR IDENTIFICADO — CONTEÚDO NÃO VALIDADO", result.state);
        assertTrue(result.giantsBannerRecognized);
        assertFalse(result.isGarArchiveValidated());
        assertFalse(result.contentReadable);
        assertFalse(result.canExtract);
        assertFalse(result.canRebuild);
        assertEquals("Não identificada", result.version);
        assertArrayEquals(before, original);
    }

    @Test public void identificaArquivoVazioEAssinaturaCurtaComoInvalidos() {
        assertEquals("ARQUIVO INVÁLIDO", inspector.inspect("vazio.gar", 0, new byte[0]).state);
        assertEquals("ARQUIVO INVÁLIDO", inspector.inspect("curto.gar", 2, new byte[]{1, 2}).state);
    }

    @Test public void naoAceitaZipRenomeadoComoGar() {
        byte[] zipMagic = {'P', 'K', 3, 4, 0, 0, 0, 0};
        GarInspector.Result result = inspector.inspect("mod.gar", zipMagic.length, zipMagic);
        assertEquals("ZIP RENOMEADO PARA GAR", result.state);
        assertFalse(result.giantsBannerRecognized);
        assertFalse(result.canExtract);
        assertFalse(result.canRebuild);
    }

    @Test public void diferenciaZipOriginalDeArquivoGar() {
        byte[] zipMagic = {'P', 'K', 5, 6, 0, 0, 0, 0};
        assertEquals("ARQUIVO ZIP", inspector.inspect("mod.zip", zipMagic.length, zipMagic).state);
    }

    @Test public void arquivoDesconhecidoFicaBloqueadoSemTentarDesproteger() {
        byte[] unknown = {0x13, 0x37, 0x02, 0x01, 0x55, 0x66};
        GarInspector.Result result = inspector.inspect("protected.gar", unknown.length, unknown);
        assertEquals("FORMATO GAR NÃO SUPORTADO", result.state);
        assertFalse(result.contentReadable);
        assertFalse(result.canExtract);
        assertFalse(result.canRebuild);
        assertTrue(result.message.contains("não foi interpretado"));
    }

    @Test public void naoLeIndicesNemCaminhosMaliciososOuDuplicadosDePayloadDesconhecido() throws Exception {
        byte[] payload = new byte[512];
        payload[0] = 0x55;
        byte[] hostileText = "../escape\0dup.txt dup.txt".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(hostileText, 0, payload, 128, hostileText.length);
        byte[] before = payload.clone();
        ByteArrayInputStream input = new ByteArrayInputStream(payload);
        GarInspector.Result result = inspector.inspect("unknown.gar", payload.length, input);
        assertFalse(result.canExtract);
        assertFalse(result.canRebuild);
        assertTrue("O inspetor deve parar no limite do cabeçalho", input.available() > 0);
        assertFalse(result.toReport().contains("../escape"));
        assertArrayEquals("Os bytes de origem devem permanecer iguais", before, payload);
    }

    @Test public void leituraDeCabecalhoRespeitaCancelamento() {
        Thread.currentThread().interrupt();
        try {
            inspector.inspect("cancel.gar", 100, new ByteArrayInputStream(new byte[100]));
            fail("a leitura interrompida não foi cancelada");
        } catch (InterruptedIOException expected) {
            assertTrue(expected.getMessage().contains("cancelada"));
        } catch (IOException unexpected) {
            fail("erro inesperado: " + unexpected.getMessage());
        } finally {
            Thread.interrupted();
        }
    }

    @Test public void erroDeLeituraRetornaFalhaSemLiberarExtracao() {
        InputStream failing = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("falha fixture"); }
            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                throw new IOException("falha fixture");
            }
        };
        try {
            inspector.inspect("unknown.gar", 100, failing);
            fail("falha de leitura ignorada");
        } catch (IOException expected) {
            assertEquals("falha fixture", expected.getMessage());
        }
    }
}
