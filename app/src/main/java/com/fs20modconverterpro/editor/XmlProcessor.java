package com.fs20modconverterpro.editor;

import org.w3c.dom.Document;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.IOException;
import java.io.StringReader;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

/** Validação e formatação XML com DTD, XXE e expansão abusiva desabilitados. */
public final class XmlProcessor {
    private static final int MAX_XML_DEPTH = 256;
    private static final int MAX_XML_NODES = 50_000;

    public XmlValidationResult validate(String xml) {
        if (xml == null || xml.trim().isEmpty()) {
            return XmlValidationResult.invalid(-1, -1, "O conteúdo XML está vazio.");
        }
        if (xml.length() > XmlDocumentCodec.MAX_XML_CHARACTERS) {
            return XmlValidationResult.invalid(-1, -1, "O XML excede o limite de tamanho do editor.");
        }

        try {
            validateWithSax(xml);
            return XmlValidationResult.valid();
        } catch (SAXParseException exception) {
            return XmlValidationResult.invalid(
                    exception.getLineNumber(),
                    exception.getColumnNumber(),
                    errorMessage(exception));
        } catch (ParserConfigurationException exception) {
            return XmlValidationResult.invalid(-1, -1,
                    "Não foi possível iniciar o validador XML seguro.");
        } catch (RuntimeException exception) {
            return XmlValidationResult.invalid(-1, -1,
                    "O dispositivo não oferece os recursos necessários para validar XML com segurança.");
        } catch (SAXException | IOException exception) {
            return XmlValidationResult.invalid(-1, -1,
                    exception.getMessage() != null && exception.getMessage().contains("limite")
                            ? exception.getMessage()
                            : "O XML não pôde ser validado com segurança.");
        }
    }

    public String format(String xml, String encoding) throws XmlProcessingException {
        XmlValidationResult validation = validate(xml);
        if (!validation.isValid()) {
            throw new XmlProcessingException(validation);
        }

        try {
            Document document = parseDom(xml);
            String formatted = XmlSerializer.format(document, xml);
            if (formatted.length() > XmlDocumentCodec.MAX_XML_CHARACTERS) {
                throw new XmlProcessingException("A formatação excedeu o limite do editor.");
            }
            return formatted;
        } catch (ParserConfigurationException | SAXException | IOException | RuntimeException exception) {
            throw new XmlProcessingException("Não foi possível formatar este XML com segurança.", exception);
        }
    }

    private static void validateWithSax(String xml)
            throws ParserConfigurationException, SAXException, IOException {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        SAXParser parser = factory.newSAXParser();
        XMLReader reader = parser.getXMLReader();
        reader.setEntityResolver(blockExternalEntities());
        BoundedXmlHandler handler = new BoundedXmlHandler();
        reader.setContentHandler(handler);
        reader.setErrorHandler(handler);
        reader.parse(new InputSource(new StringReader(xml)));
    }

    private static Document parseDom(String xml)
            throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver(blockExternalEntities());
        builder.setErrorHandler(new BoundedXmlHandler());
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    private static EntityResolver blockExternalEntities() {
        return (publicId, systemId) -> {
            throw new SAXException("Resolução de entidades externas não permitida.");
        };
    }

    private static String errorMessage(SAXParseException exception) {
        int line = exception.getLineNumber();
        int column = exception.getColumnNumber();
        String message;
        String detail = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase();
        if (detail.contains("doctype") || detail.contains("doctype-decl")) {
            message = "DTD e entidades externas não são permitidos.";
        } else if (detail.contains("limite")) {
            message = "O XML excedeu um limite seguro de processamento.";
        } else {
            message = "Verifique as tags, atributos e valores do XML.";
        }
        if (line > 0) {
            return column > 0
                    ? "XML inválido na linha " + line + ", coluna " + column + ". " + message
                    : "XML inválido na linha " + line + ". " + message;
        }
        return "XML inválido. " + message;
    }

    private static final class BoundedXmlHandler extends DefaultHandler {
        private int depth;
        private int nodes;

        @Override
        public void startElement(String uri, String localName, String qualifiedName,
                                 org.xml.sax.Attributes attributes) throws SAXException {
            depth++;
            nodes++;
            if (depth > MAX_XML_DEPTH || nodes > MAX_XML_NODES) {
                throw new SAXException("O XML excede um limite seguro de estrutura.");
            }
        }

        @Override
        public void endElement(String uri, String localName, String qualifiedName) {
            depth--;
        }

        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }
    }

    public static final class XmlProcessingException extends Exception {
        private final int line;
        private final int column;

        public XmlProcessingException(String message) {
            super(message);
            line = -1;
            column = -1;
        }

        public XmlProcessingException(String message, Throwable cause) {
            super(message, cause);
            line = -1;
            column = -1;
        }

        XmlProcessingException(XmlValidationResult result) {
            super(result.getMessage());
            line = result.getLine();
            column = result.getColumn();
        }

        public int getLine() {
            return line;
        }

        public int getColumn() {
            return column;
        }
    }
}
