package com.fs20modconverterpro.viewer3d;

import org.junit.Test;
import java.util.Arrays;
import java.util.HashSet;
import org.xml.sax.SAXException;
import static org.junit.Assert.*;

public class I3dAnalyzerTest {
    @Test public void analisaEstruturaEReferenciasAusentes() throws Exception {String xml="<i3d><Files><File fileId=\"1\" filename=\"textures/body.dds\"/></Files><Scene><Node><TransformGroup><Shape/></TransformGroup><Material/></Node></Scene></i3d>";I3dAnalyzer.Report r=new I3dAnalyzer().analyze(xml.getBytes("UTF-8"),new HashSet<>(Arrays.asList("other.dds")));assertEquals(1,r.nodes);assertEquals(1,r.transforms);assertEquals(1,r.materials);assertEquals(1,r.models);assertEquals(1,r.missingTextures.size());}
    @Test public void rejeitaDtdEEntidadeExterna() throws Exception {String xml="<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><i3d>&e;</i3d>";try{new I3dAnalyzer().analyze(xml.getBytes("UTF-8"),null);fail("DOCTYPE e entidade externa devem ser bloqueados");}catch(SAXException expected){assertTrue(expected.getMessage().contains("DOCTYPE")||expected.getMessage().contains("entidade"));}}
    @Test public void rejeitaDtdExternoSemTentarCarregaLo() throws Exception {String xml="<!DOCTYPE i3d SYSTEM 'http://127.0.0.1:1/externo.dtd'><i3d/>";try{new I3dAnalyzer().analyze(xml.getBytes("UTF-8"),null);fail("DTD externo deve ser bloqueado");}catch(SAXException expected){assertNotNull(expected.getMessage());}}
    @Test public void analisaI3dValidoSemProcessarXInclude() throws Exception {String xml="<i3d xmlns:xi=\"http://www.w3.org/2001/XInclude\"><xi:include href=\"file:///etc/passwd\" parse=\"text\"/><Scene/></i3d>";I3dAnalyzer.Report r=new I3dAnalyzer().analyze(xml.getBytes("UTF-8"),null);assertEquals(0,r.nodes);assertTrue(r.referencesUnverified);}
    @Test(expected=IllegalArgumentException.class) public void bloqueiaI3dGrande() throws Exception {new I3dAnalyzer().analyze(new byte[I3dAnalyzer.MAX_BYTES+1],null);}
}
