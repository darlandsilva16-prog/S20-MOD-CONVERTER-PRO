package com.fs20modconverterpro.gar.synthetic;

import android.content.Context;

import com.fs20modconverterpro.gar.GarPythonBridge;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class GarPythonBridgeSyntheticInstrumentedTest {
    private static final long SAMPLE_SIZE = 36095240L;

    @Test public void importaEMontaOModuloGarPyNoChaquopySobDemanda() throws Exception {
        byte[] header = observedHeader();
        byte[] before = header.clone();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String json = new GarPythonBridge().analyzeHeader(context, "dataS.gar", SAMPLE_SIZE, header);
        JSONObject report = new JSONObject(json);
        assertEquals("GAR ENGINE PRO", report.getString("module"));
        assertEquals("CABECALHO_IDENTIFICADO", report.getString("state_code"));
        assertEquals("ESTRUTURA_NAO_VALIDADA", report.getString("index_state_code"));
        assertFalse(report.getBoolean("index_read_available"));
        assertEquals(14, report.getInt("giants_banner_offset"));
        assertEquals(96, report.getInt("gar_signature_offset"));
        JSONArray fields = report.getJSONArray("observed_fields");
        assertEquals(3, fields.length());
        assertEquals(4, fields.getJSONObject(0).getInt("observed_value"));
        assertEquals(16, fields.getJSONObject(0).getInt("width_bits"));
        assertEquals(5, fields.getJSONObject(1).getInt("observed_value"));
        assertEquals(16, fields.getJSONObject(1).getInt("width_bits"));
        assertEquals(2212, fields.getJSONObject(2).getInt("observed_value"));
        assertEquals(32, fields.getJSONObject(2).getInt("width_bits"));
        assertFalse(report.getBoolean("can_extract"));
        assertFalse(report.getBoolean("can_rebuild"));
        assertFalse(report.getBoolean("can_search_contents"));
        assertTrue(report.getBoolean("read_only"));
        assertArrayEquals(before, header);
    }

    private static byte[] observedHeader() {
        byte[] bytes = new byte[128];
        byte[] banner = "Copyright (C) GIANTS Software GmbH, All Rights Reserved".getBytes();
        System.arraycopy(banner, 0, bytes, 14, banner.length);
        System.arraycopy(new byte[]{'G', 'A', 'R', ' ', 4, 0, 5, 0, (byte) 0xA4, 8, 0, 0}, 0, bytes, 96, 12);
        return bytes;
    }
}
