package com.fs20modconverterpro.viewer3d;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import static org.junit.Assert.*;

public class GiantsShapesDecoderTest {
    @Test public void leFormaTrianguloEInterpretaIndiceNormalEUv() throws Exception {
        GiantsShapesDecoder.Shape shape=new GiantsShapesDecoder().readShape(triangleEntity(),4);
        assertEquals("tri",shape.name);assertEquals(37L,shape.id);
        assertEquals(3,shape.positions.length);assertEquals(3,shape.indices.length);
        assertArrayEquals(new int[]{0,1,2},shape.indices);
        assertEquals(1,shape.uvSets);assertNotNull(shape.normals);
        assertEquals(1f,shape.positions[1][0],0.0001f);
    }
    @Test public void recusaIndiceForaDaGeometria() throws Exception {
        byte[] data=triangleEntity();
        // Primeiro índice segue cabeçalho/nome, limite, subset e começa no byte 60.
        data[60]=9;
        try { new GiantsShapesDecoder().readShape(data,4); fail("índice inválido aceito"); }
        catch(IOException expected){assertTrue(expected.getMessage().contains("Índice"));}
    }
    private static byte[] triangleEntity() throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        leInt(out,3);out.write(new byte[]{'t','r','i'});out.write(0);leInt(out,37);
        for(int i=0;i<4;i++)leFloat(out,1); // volume
        leInt(out,3);leInt(out,1);leInt(out,3);leInt(out,3); // contagens e normais+UV1
        leInt(out,0);leInt(out,3);leInt(out,0);leInt(out,3); // grupo
        leShort(out,0);leShort(out,1);leShort(out,2);out.write(0);out.write(0); // alinhamento
        leFloat(out,0);leFloat(out,0);leFloat(out,0);leFloat(out,1);leFloat(out,0);leFloat(out,0);leFloat(out,0);leFloat(out,1);leFloat(out,0);
        for(int i=0;i<3;i++){leFloat(out,0);leFloat(out,0);leFloat(out,1);} // normais
        leFloat(out,0);leFloat(out,0);leFloat(out,1);leFloat(out,0);leFloat(out,0);leFloat(out,1); // UVs
        leInt(out,0); // anexos
        return bytes.toByteArray();
    }
    private static void leInt(DataOutputStream out,int v)throws IOException{out.write(v);out.write(v>>>8);out.write(v>>>16);out.write(v>>>24);}
    private static void leShort(DataOutputStream out,int v)throws IOException{out.write(v);out.write(v>>>8);}
    private static void leFloat(DataOutputStream out,float v)throws IOException{leInt(out,Float.floatToIntBits(v));}
}
