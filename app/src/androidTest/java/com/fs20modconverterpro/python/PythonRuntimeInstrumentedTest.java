package com.fs20modconverterpro.python;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class PythonRuntimeInstrumentedTest {
    private PythonBridge bridge;
    @Before public void startRuntime() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        bridge = new PythonBridge(); bridge.start(context);
    }
    @Test public void iniciaPythonDentroDoApk() throws Exception {
        String info = bridge.runtimeInfo();
        assertTrue(info.contains("3.11")); assertTrue(info.contains("PRONTO"));
    }
    @Test public void enviaZipJavaParaPythonESemExtrair() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("mod/modDesc.xml")); zip.write("<modDesc/>".getBytes("UTF-8")); zip.closeEntry();
        }
        String report = bridge.analyze("mod.zip", bytes.toByteArray());
        assertTrue(report.contains("mod/modDesc.xml")); assertTrue(report.contains("ANALISADO"));
    }
    @Test public void analisaXmlSemExecutarConteudo() {
        String report = bridge.analyze("mod.xml", "<modDesc><storeItems/></modDesc>".getBytes());
        assertTrue(report.contains("VALIDO"));
    }
    @Test public void recusaXmlMalformado() {
        try { bridge.analyze("broken.xml", "<root>".getBytes()); fail("XML inválido deveria gerar erro"); }
        catch (RuntimeException expected) { assertNotNull(expected.getMessage()); }
    }
    @Test public void luaEAnalisadoSemExecutar() {
        String report = bridge.analyze("script.lua", "print('texto de teste')".getBytes());
        assertTrue(report.contains("ANALISE_ESTATICA"));
        assertTrue(report.contains("não é validação por interpretador Lua"));
    }
}
