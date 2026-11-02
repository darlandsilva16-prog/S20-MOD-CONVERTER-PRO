package com.fs20modconverterpro.viewer3d;

import org.junit.Test;
import java.util.Arrays;
import java.util.HashSet;
import static org.junit.Assert.*;

public class I3dAnalyzerTest {
    @Test public void analisaEstruturaEReferenciasAusentes() throws Exception {String xml="<i3d><Files><File fileId=\"1\" filename=\"textures/body.dds\"/></Files><Scene><Node><TransformGroup><Shape/></TransformGroup><Material/></Node></Scene></i3d>";I3dAnalyzer.Report r=new I3dAnalyzer().analyze(xml.getBytes("UTF-8"),new HashSet<>(Arrays.asList("other.dds")));assertEquals(1,r.nodes);assertEquals(1,r.transforms);assertEquals(1,r.materials);assertEquals(1,r.models);assertEquals(1,r.missingTextures.size());}
    @Test(expected=Exception.class) public void rejeitaDtdEEntidadeExterna() throws Exception {String xml="<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><i3d>&e;</i3d>";new I3dAnalyzer().analyze(xml.getBytes("UTF-8"),null);}
    @Test(expected=IllegalArgumentException.class) public void bloqueiaI3dGrande() throws Exception {new I3dAnalyzer().analyze(new byte[I3dAnalyzer.MAX_BYTES+1],null);}
}
