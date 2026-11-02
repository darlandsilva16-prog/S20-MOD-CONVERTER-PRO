package com.fs20modconverterpro.viewer3d;

import org.xml.sax.Attributes;
import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.xml.parsers.SAXParserFactory;

/** Análise estrutural I3D em modo somente leitura; não renderiza recursos proprietários. */
public final class I3dAnalyzer {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int MAX_REFS = 10000;

    public Report analyze(byte[] xml, Set<String> archivePaths) throws Exception {
        if (xml == null || xml.length == 0 || xml.length > MAX_BYTES) throw new IllegalArgumentException("I3D vazio ou acima do limite de 16 MiB.");
        Handler handler = new Handler();
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(false);
        try {
            factory.setXIncludeAware(false);
        } catch (RuntimeException unsupported) {
            // Sem confirmação de XInclude desativado, não analisamos o documento.
            throw new SAXException("O parser não confirmou a desativação de XInclude.", unsupported);
        }
        // Rejeitar DOCTYPE também impede que o documento declare entidades internas
        // que poderiam ser expandidas durante a leitura SAX.
        setRequiredFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setRequiredFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        setRequiredFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        setRequiredFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        javax.xml.parsers.SAXParser parser = factory.newSAXParser();
        XMLReader reader = parser.getXMLReader();
        // Reaplica os controles diretamente ao leitor usado na análise. Se o parser
        // não aceitar qualquer proteção obrigatória, a operação falha de forma segura.
        setRequiredFeature(reader, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setRequiredFeature(reader, "http://xml.org/sax/features/external-general-entities", false);
        setRequiredFeature(reader, "http://xml.org/sax/features/external-parameter-entities", false);
        setRequiredFeature(reader, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        reader.setEntityResolver(new DenyExternalEntities());
        reader.setErrorHandler(new StrictErrorHandler());
        reader.setContentHandler(handler);
        reader.parse(new InputSource(new ByteArrayInputStream(xml)));
        ArrayList<String> missing = new ArrayList<>();
        if (archivePaths != null) for (String ref : handler.textures) {
            String normalized = ref.replace('\\', '/');
            if (!archivePaths.contains(normalized) && !archivePaths.contains(baseName(normalized))) missing.add(ref);
        }
        return new Report(handler.nodes, handler.transforms, handler.materials, handler.models,
                handler.textures, missing, archivePaths == null);
    }

    private static String baseName(String value) { int i = value.lastIndexOf('/'); return i < 0 ? value : value.substring(i + 1); }

    private static void setRequiredFeature(SAXParserFactory factory, String name, boolean enabled)
            throws SAXException {
        try {
            factory.setFeature(name, enabled);
        } catch (Exception unsupported) {
            throw new SAXException("O parser não oferece a proteção XML obrigatória: " + name, unsupported);
        }
    }

    private static void setRequiredFeature(XMLReader reader, String name, boolean enabled)
            throws SAXException {
        try {
            reader.setFeature(name, enabled);
        } catch (SAXException | RuntimeException unsupported) {
            throw new SAXException("O parser não oferece a proteção XML obrigatória: " + name, unsupported);
        }
    }

    /** Bloqueia qualquer resolução de recurso externo mesmo se o parser ignorar uma opção. */
    private static final class DenyExternalEntities implements EntityResolver {
        @Override public InputSource resolveEntity(String publicId, String systemId) throws SAXException {
            throw new SAXException("Referências a entidades externas não são permitidas em arquivos I3D.");
        }
    }

    private static final class StrictErrorHandler implements ErrorHandler {
        @Override public void warning(SAXParseException exception) throws SAXException { throw exception; }
        @Override public void error(SAXParseException exception) throws SAXException { throw exception; }
        @Override public void fatalError(SAXParseException exception) throws SAXException { throw exception; }
    }

    private static final class Handler extends DefaultHandler {
        int nodes, transforms, materials, models;
        final List<String> textures = new ArrayList<>();
        @Override public void startElement(String uri, String local, String qName, Attributes a) {
            String tag = qName.toLowerCase(Locale.ROOT);
            if (tag.equals("node")) nodes++;
            if (tag.equals("transform") || tag.startsWith("transformgroup")) transforms++;
            if (tag.equals("material")) materials++;
            if (tag.equals("shape") || tag.equals("model")) models++;
            for (int i = 0; i < a.getLength() && textures.size() < MAX_REFS; i++) {
                String name = a.getQName(i).toLowerCase(Locale.ROOT);
                String value = a.getValue(i).trim();
                if (!value.isEmpty() && (name.contains("texture") || name.equals("normalmap") || name.equals("filename"))) {
                    String lower = value.toLowerCase(Locale.ROOT);
                    if (lower.endsWith(".png") || lower.endsWith(".dds") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".tga") || lower.endsWith(".bmp")) {
                        if (!textures.contains(value)) textures.add(value);
                    }
                }
            }
        }
    }

    public static final class Report {
        public final int nodes, transforms, materials, models;
        public final List<String> textureReferences, missingTextures;
        public final boolean referencesUnverified;
        Report(int n,int t,int m,int mo,List<String> refs,List<String> missing,boolean unverified) {
            nodes=n; transforms=t; materials=m; models=mo;
            textureReferences=Collections.unmodifiableList(new ArrayList<>(refs));
            missingTextures=Collections.unmodifiableList(missing); referencesUnverified=unverified;
        }
        public String summary() {
            return "Análise I3D somente leitura\nNós: " + nodes + "\nTransformações: " + transforms + "\nReferências de modelo/forma: " + models + "\nMateriais: " + materials +
                    "\nTexturas referenciadas: " + textureReferences.size() + (referencesUnverified ? "\nExistência de referências não verificada fora de um ZIP." : "\nReferências ausentes: " + missingTextures.size()) +
                    "\n\nRenderização completa de I3D não está implementada; o formato contém recursos específicos do jogo.";
        }
    }
}
