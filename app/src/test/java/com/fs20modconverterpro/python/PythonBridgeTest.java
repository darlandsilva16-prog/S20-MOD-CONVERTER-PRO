package com.fs20modconverterpro.python;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PythonBridgeTest {
    @Test public void aceitaConteudoLimitado() { PythonBridge.validateInput("mod.zip", new byte[]{1}); }
    @Test public void bloqueiaNomeAusente() {
        try { PythonBridge.validateInput("", new byte[]{1}); fail("nome vazio aceito"); }
        catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
    }
    @Test public void bloqueiaArquivoVazio() {
        try { PythonBridge.validateInput("mod.xml", new byte[0]); fail("arquivo vazio aceito"); }
        catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
    }
}
