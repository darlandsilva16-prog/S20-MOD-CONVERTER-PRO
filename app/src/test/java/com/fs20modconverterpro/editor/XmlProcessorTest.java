package com.fs20modconverterpro.editor;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class XmlProcessorTest {
    private final XmlProcessor processor = new XmlProcessor();

    @Test
    public void validaXmlBemFormado() {
        XmlValidationResult result = processor.validate("<root><item value=\"1\"/></root>");

        assertTrue(result.isValid());
        assertTrue(result.getMessage().contains("válido"));
    }

    @Test
    public void informaLinhaEColunaDeXmlMalformado() {
        XmlValidationResult result = processor.validate("<root>\n  <item>\n</root>");

        assertFalse(result.isValid());
        assertTrue(result.getLine() > 0);
        assertTrue(result.getColumn() > 0);
        assertTrue(result.getMessage().contains("linha"));
    }

    @Test
    public void rejeitaDoctypeEEntidadesExternas() {
        String xml = "<!DOCTYPE root [<!ENTITY segredo SYSTEM 'file:///etc/passwd'>]>"
                + "<root>&segredo;</root>";

        XmlValidationResult result = processor.validate(xml);

        assertFalse(result.isValid());
        assertTrue(result.getLine() > 0);
    }

    @Test
    public void formataEValidaNovamenteOResultado() throws Exception {
        String formatted = processor.format("<root><item id=\"1\">conteúdo</item></root>", "UTF-8");

        assertTrue(formatted.contains("\n"));
        assertTrue(processor.validate(formatted).isValid());
        assertTrue(formatted.contains("conteúdo"));
    }

    @Test
    public void preservaTextoMistoAoFormatar() throws Exception {
        String formatted = processor.format("<p>antes<b>forte</b>depois</p>", "UTF-8");

        assertTrue(formatted.contains("antes<b>forte</b>depois"));
        assertTrue(processor.validate(formatted).isValid());
    }

    @Test
    public void preservaEspacosMarcadosComXmlSpace() throws Exception {
        String formatted = processor.format(
                "<root xml:space=\"preserve\"><a/><b/></root>", "UTF-8");

        assertTrue(formatted.contains("<a/><b/>"));
    }

    @Test
    public void preservaEspacosDeTextoMisto() throws Exception {
        String formatted = processor.format("<p> <b/> </p>", "UTF-8");

        assertTrue(formatted.contains("<p> <b/> </p>"));
    }

    @Test
    public void recusaProfundidadeExcessiva() {
        StringBuilder xml = new StringBuilder();
        for (int index = 0; index < 300; index++) {
            xml.append("<n>");
        }
        for (int index = 0; index < 300; index++) {
            xml.append("</n>");
        }

        assertFalse(processor.validate(xml.toString()).isValid());
    }
}
