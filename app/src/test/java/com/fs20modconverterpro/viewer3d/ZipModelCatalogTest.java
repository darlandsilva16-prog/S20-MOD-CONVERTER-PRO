package com.fs20modconverterpro.viewer3d;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

public class ZipModelCatalogTest {
    private byte[] zip() throws Exception {ByteArrayOutputStream b=new ByteArrayOutputStream();ZipOutputStream z=new ZipOutputStream(b);z.putNextEntry(new ZipEntry("models/truck.obj"));z.write("v 0 0 0".getBytes("UTF-8"));z.closeEntry();z.putNextEntry(new ZipEntry("../../escape.stl"));z.write(1);z.closeEntry();z.close();return b.toByteArray();}
    @Test public void listaELeModeloSemGravarNoDisco() throws Exception {byte[] z=zip();ZipModelCatalog c=new ZipModelCatalog();assertEquals(2,c.list(z).size());assertEquals("v 0 0 0",new String(c.read(z,"models/truck.obj"),"UTF-8"));}
    @Test(expected=java.io.IOException.class) public void rejeitaZipAcimaDoLimite() throws Exception {new ZipModelCatalog().list(new byte[ZipModelCatalog.MAX_ARCHIVE_BYTES+1]);}
}
