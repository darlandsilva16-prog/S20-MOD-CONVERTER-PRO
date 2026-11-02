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
    @Test public void analisaI3dValidoDePlantadeiraSemXInclude() throws Exception {String xml="<i3d><Files><File fileId=\"1\" filename=\"textures/plantadeira_diffuse.dds\"/><File fileId=\"2\" filename=\"plantadeira.i3d.shapes\"/></Files><Materials><Material name=\"Pintura\" diffuseColor=\"1 1 1 1\"/></Materials><Shapes><Shape shapeId=\"1\" name=\"chassi\"/></Shapes><Scene><TransformGroup name=\"plantadeira\"><Shape shapeId=\"1\"/></TransformGroup></Scene></i3d>";I3dAnalyzer.Report r=new I3dAnalyzer().analyze(xml.getBytes("UTF-8"),null);assertEquals(1,r.transforms);assertEquals(1,r.materials);assertEquals(2,r.models);assertEquals(1,r.textureReferences.size());assertTrue(r.referencesUnverified);}
    @Test public void rejeitaElementoXIncludeExplicitamente() throws Exception {String xml="<i3d xmlns:xi=\"http://www.w3.org/2001/XInclude\"><xi:include href=\"file:///etc/passwd\" parse=\"text\"/><Scene/></i3d>";try{new I3dAnalyzer().analyze(xml.getBytes("UTF-8"),null);fail("Elemento XInclude deve ser rejeitado");}catch(SAXException expected){assertTrue(expected.getMessage().contains("XInclude"));}}
    @Test public void rejeitaDtdAposComentario() throws Exception {String xml="<!-- comentário válido antes do DTD --><!DOCTYPE i3d [<!ENTITY x 'y'>]><i3d/>";assertXmlRejected(xml.getBytes("UTF-8"));}
    @Test public void rejeitaEntidadeExternaDeParametro() throws Exception {String xml="<!DOCTYPE i3d [<!ENTITY % externo SYSTEM 'file:///etc/passwd'>%externo;]><i3d/>";assertXmlRejected(xml.getBytes("UTF-8"));}
    @Test public void naoConfundeTextoDtdDentroDeComentarioComDeclaracao() throws Exception {String xml="<!-- exemplo literal: <!DOCTYPE falso> --><i3d><Scene/></i3d>";assertEquals(0,new I3dAnalyzer().analyze(xml.getBytes("UTF-8"),null).nodes);}
    @Test public void rejeitaDtdCodificadoEmUtf16Le() throws Exception {String xml="<!DOCTYPE i3d [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><i3d>&x;</i3d>";byte[] body=xml.getBytes("UTF-16LE");byte[] encoded=new byte[body.length+2];encoded[0]=(byte)0xff;encoded[1]=(byte)0xfe;System.arraycopy(body,0,encoded,2,body.length);assertXmlRejected(encoded);}
    @Test public void analisaXmlValidoCodificadoEmIso88591() throws Exception {String xml="<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><i3d><Material name=\"Pintura café\"/></i3d>";assertEquals(1,new I3dAnalyzer().analyze(xml.getBytes("ISO-8859-1"),null).materials);}
    private static void assertXmlRejected(byte[] xml) throws Exception {try{new I3dAnalyzer().analyze(xml,null);fail("XML com DTD deveria ser rejeitado");}catch(SAXException expected){assertNotNull(expected.getMessage());}}
    @Test(expected=IllegalArgumentException.class) public void bloqueiaI3dGrande() throws Exception {new I3dAnalyzer().analyze(new byte[I3dAnalyzer.MAX_BYTES+1],null);}
}
