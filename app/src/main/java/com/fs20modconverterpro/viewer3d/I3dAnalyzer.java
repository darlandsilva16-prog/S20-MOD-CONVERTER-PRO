package com.fs20modconverterpro.viewer3d;

import org.xml.sax.Attributes;
import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.SAXParserFactory;

/** Análise estrutural I3D em modo somente leitura; não renderiza recursos proprietários. */
public final class I3dAnalyzer {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int MAX_REFS = 10000;
    private static final String XINCLUDE_NAMESPACE = "http://www.w3.org/2001/XInclude";
    private static final Pattern XML_ENCODING = Pattern.compile("<\\?xml[^>]*\\bencoding\\s*=\\s*['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);

    public Report analyze(byte[] xml, Set<String> archivePaths) throws Exception {
        if (xml == null || xml.length == 0 || xml.length > MAX_BYTES) throw new IllegalArgumentException("I3D vazio ou acima do limite de 16 MiB.");
        String source = decodeXml(xml);
        rejectForbiddenDeclarations(source);
        Handler handler = new Handler();
        SAXParserFactory factory = SAXParserFactory.newInstance();
        // SAX não processa XInclude por padrão. Namespace ligado permite recusar
        // explicitamente qualquer elemento XInclude, sem consultar recursos que
        // não existem em algumas implementações Android.
        factory.setNamespaceAware(true);
        try {
            factory.setXIncludeAware(false);
        } catch (UnsupportedOperationException unsupported) {
            // Implementações SAX sem XInclude não podem processar essa diretiva;
            // se houver suporte, ele fica explicitamente desativado.
        }
        // Essas opções são defesa adicional. A barreira obrigatória é a pré-validação
        // lexical acima, que rejeita qualquer DOCTYPE antes de o SAX receber o texto.
        disableFeatureIfSupported(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        disableFeatureIfSupported(factory, "http://xml.org/sax/features/external-general-entities", false);
        disableFeatureIfSupported(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        disableFeatureIfSupported(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        javax.xml.parsers.SAXParser parser = factory.newSAXParser();
        XMLReader reader = parser.getXMLReader();
        disableFeatureIfSupported(reader, "http://apache.org/xml/features/disallow-doctype-decl", true);
        disableFeatureIfSupported(reader, "http://xml.org/sax/features/external-general-entities", false);
        disableFeatureIfSupported(reader, "http://xml.org/sax/features/external-parameter-entities", false);
        disableFeatureIfSupported(reader, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        reader.setEntityResolver(new DenyExternalEntities());
        reader.setErrorHandler(new StrictErrorHandler());
        reader.setContentHandler(handler);
        reader.parse(new InputSource(new StringReader(source)));
        ArrayList<String> missing = new ArrayList<>();
        if (archivePaths != null) for (String ref : handler.textures) {
            String normalized = ref.replace('\\', '/');
            if (!archivePaths.contains(normalized) && !archivePaths.contains(baseName(normalized))) missing.add(ref);
        }
        return new Report(handler.nodes, handler.transforms, handler.materials, handler.models,
                handler.textures, missing, archivePaths == null);
    }

    private static String baseName(String value) { int i = value.lastIndexOf('/'); return i < 0 ? value : value.substring(i + 1); }

    private static void disableFeatureIfSupported(SAXParserFactory factory, String name, boolean value) {
        try {
            factory.setFeature(name, value);
        } catch (Exception ignored) { /* A pré-validação e o resolvedor garantem a proteção. */ }
    }

    private static void disableFeatureIfSupported(XMLReader reader, String name, boolean value) {
        try {
            reader.setFeature(name, value);
        } catch (SAXException | RuntimeException ignored) { /* O SAX só recebe XML já pré-validado. */ }
    }

    /** Decodifica conforme BOM/assinatura/declaração para validar exatamente os caracteres entregues ao SAX. */
    private static String decodeXml(byte[] bytes) throws SAXException {
        int offset = 0;
        Charset charset = StandardCharsets.UTF_8;
        if (starts(bytes, 0x00, 0x00, 0xfe, 0xff)) { charset = Charset.forName("UTF-32BE"); offset = 4; }
        else if (starts(bytes, 0xff, 0xfe, 0x00, 0x00)) { charset = Charset.forName("UTF-32LE"); offset = 4; }
        else if (starts(bytes, 0xef, 0xbb, 0xbf)) { offset = 3; }
        else if (starts(bytes, 0xfe, 0xff)) { charset = StandardCharsets.UTF_16BE; offset = 2; }
        else if (starts(bytes, 0xff, 0xfe)) { charset = StandardCharsets.UTF_16LE; offset = 2; }
        else if (starts(bytes, 0x00, 0x00, 0x00, 0x3c)) { charset = Charset.forName("UTF-32BE"); }
        else if (starts(bytes, 0x3c, 0x00, 0x00, 0x00)) { charset = Charset.forName("UTF-32LE"); }
        else if (starts(bytes, 0x00, 0x3c, 0x00, 0x3f)) { charset = StandardCharsets.UTF_16BE; }
        else if (starts(bytes, 0x3c, 0x00, 0x3f, 0x00)) { charset = StandardCharsets.UTF_16LE; }
        else {
            String header = new String(bytes, 0, Math.min(bytes.length, 512), StandardCharsets.ISO_8859_1);
            Matcher declaration = XML_ENCODING.matcher(header);
            if (declaration.find()) {
                try { charset = Charset.forName(declaration.group(1)); }
                catch (RuntimeException unsupported) { throw new SAXException("Codificação XML não suportada.", unsupported); }
            }
        }
        try {
            return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        } catch (CharacterCodingException malformed) {
            throw new SAXException("Codificação inválida no arquivo I3D.", malformed);
        }
    }

    private static boolean starts(byte[] data, int... signature) {
        if (data.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) if ((data[i] & 0xff) != signature[i]) return false;
        return true;
    }

    /** Rejeita DTDs no texto decodificado antes que qualquer parser possa expandi-los. */
    private static void rejectForbiddenDeclarations(String xml) throws SAXException {
        int i = 0;
        while (i < xml.length()) {
            if (xml.startsWith("<!--", i)) {
                int end = xml.indexOf("-->", i + 4);
                if (end < 0) throw new SAXException("Comentário XML não terminado.");
                i = end + 3;
            } else if (xml.startsWith("<![CDATA[", i)) {
                int end = xml.indexOf("]]>", i + 9);
                if (end < 0) throw new SAXException("Seção CDATA não terminada.");
                i = end + 3;
            } else if (xml.startsWith("<?", i)) {
                int end = xml.indexOf("?>", i + 2);
                if (end < 0) throw new SAXException("Instrução de processamento XML não terminada.");
                i = end + 2;
            } else if (xml.startsWith("<!", i)) {
                if (xml.regionMatches(true, i, "<!DOCTYPE", 0, 9)) {
                    throw new SAXException("DOCTYPE e declarações DTD não são permitidos em arquivos I3D.");
                }
                throw new SAXException("Declaração XML não permitida antes da análise I3D.");
            } else if (xml.charAt(i) == '<') {
                i = skipTag(xml, i);
            } else {
                i++;
            }
        }
    }

    private static int skipTag(String xml, int start) throws SAXException {
        char quote = 0;
        for (int i = start + 1; i < xml.length(); i++) {
            char c = xml.charAt(i);
            if (quote != 0) { if (c == quote) quote = 0; }
            else if (c == '\'' || c == '"') quote = c;
            else if (c == '>') return i + 1;
        }
        throw new SAXException("Tag XML não terminada.");
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
        @Override public void startElement(String uri, String local, String qName, Attributes a) throws SAXException {
            if (XINCLUDE_NAMESPACE.equals(uri)) {
                throw new SAXException("Elementos XInclude não são permitidos em arquivos I3D.");
            }
            String elementName = local == null || local.isEmpty() ? qName : local;
            String tag = elementName.toLowerCase(Locale.ROOT);
            if (tag.equals("node")) nodes++;
            if (tag.equals("transform") || tag.startsWith("transformgroup")) transforms++;
            if (tag.equals("material")) materials++;
            if (tag.equals("shape") || tag.equals("model")) models++;
            for (int i = 0; i < a.getLength() && textures.size() < MAX_REFS; i++) {
                String localAttribute = a.getLocalName(i);
                String name = (localAttribute == null || localAttribute.isEmpty()
                        ? a.getQName(i) : localAttribute).toLowerCase(Locale.ROOT);
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
