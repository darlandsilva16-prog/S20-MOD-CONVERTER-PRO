package com.fs20modconverterpro.gar.synthetic;

import com.fs20modconverterpro.gar.GarIndexReaderRegistry;
import com.fs20modconverterpro.gar.GarInspector;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Confirma que o ponto de extensão permanece vazio sem especificação ou amostra validada. */
public final class GarIndexReaderRegistrySyntheticTest {
    @Test public void registroInicialNaoDeclaraLeitorNemParser() {
        GarIndexReaderRegistry registry = new GarIndexReaderRegistry();

        assertEquals(0, registry.readers().size());
        assertNull(registry.findReader(new GarInspector().inspect("fixture-sintetico.gar", 0, new byte[0])));
        assertNull(registry.findReader(null));
    }
}
