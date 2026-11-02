package com.fs20modconverterpro.viewer3d;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Leitor glTF 2.0 para malhas triangulares autocontidas e atributos POSITION suportados. */
final class GltfModelLoader {
    private static final int GLB_MAGIC = 0x46546c67;
    private static final int JSON_CHUNK = 0x4e4f534a;
    private static final int BIN_CHUNK = 0x004e4942;
    private static final int MAX_TOTAL_BUFFER_BYTES = 32 * 1024 * 1024;

    ModelLoadResult loadGltf(byte[] bytes) throws ModelFormatException {
        String json = ModelLoader.decodeUtf8(bytes);
        return loadDocument(json, null);
    }

    ModelLoadResult loadGlb(byte[] bytes) throws ModelFormatException {
        if (bytes.length < 20 || u32(bytes, 0) != GLB_MAGIC || u32(bytes, 4) != 2
                || u32(bytes, 8) != bytes.length) throw new ModelFormatException("Cabeçalho GLB inválido; é necessário GLB versão 2.");
        int cursor = 12;
        String json = null;
        byte[] binary = null;
        while (cursor + 8 <= bytes.length) {
            long chunkLength = u32(bytes, cursor);
            int chunkType = (int) u32(bytes, cursor + 4);
            cursor += 8;
            if (chunkLength > bytes.length - cursor) throw new ModelFormatException("Bloco interno GLB truncado.");
            int length = (int) chunkLength;
            if (chunkType == JSON_CHUNK && json == null) {
                byte[] chunk = Arrays.copyOfRange(bytes, cursor, cursor + length);
                json = ModelLoader.decodeUtf8(chunk).replace("\u0000", "").trim();
            } else if (chunkType == BIN_CHUNK && binary == null) {
                binary = Arrays.copyOfRange(bytes, cursor, cursor + length);
            }
            cursor += length;
        }
        if (json == null) throw new ModelFormatException("GLB sem bloco JSON obrigatório.");
        return loadDocument(json, binary);
    }

    private ModelLoadResult loadDocument(String json, byte[] glbBinary) throws ModelFormatException {
        Object parsed = MiniJsonParser.parse(json);
        Map<String, Object> root = object(parsed, "raiz glTF");
        Map<String, Object> asset = object(root.get("asset"), "asset glTF");
        if (!"2.0".equals(string(asset.get("version")))) throw new ModelFormatException("Somente glTF 2.0 é suportado.");
        List<Object> bufferSpecs = array(root.get("buffers"), "buffers");
        ArrayList<byte[]> buffers = new ArrayList<>();
        long totalBufferBytes = 0;
        for (int index = 0; index < bufferSpecs.size(); index++) {
            Map<String, Object> spec = object(bufferSpecs.get(index), "buffer glTF");
            long expectedLength = integer(spec.get("byteLength"), -1);
            if (expectedLength < 0 || expectedLength > MAX_TOTAL_BUFFER_BYTES)
                throw new ModelFormatException("Tamanho de buffer glTF inválido ou excessivo.");
            byte[] buffer;
            String uri = string(spec.get("uri"));
            if (uri != null) {
                if (!uri.startsWith("data:")) {
                    return nonRenderable(ModelFormat.GLTF,
                            "glTF reconhecido, mas a malha depende de buffer externo que não veio junto com o arquivo.");
                }
                buffer = decodeDataUri(uri);
            } else if (glbBinary != null && index == 0) {
                buffer = glbBinary;
            } else {
                return nonRenderable(ModelFormat.GLTF, "glTF referencia um buffer externo ausente.");
            }
            if (buffer.length < expectedLength) throw new ModelFormatException("Buffer glTF menor que o byteLength declarado.");
            totalBufferBytes += buffer.length;
            if (totalBufferBytes > MAX_TOTAL_BUFFER_BYTES) throw new ModelFormatException("Buffers glTF excedem 32 MiB.");
            buffers.add(buffer);
        }

        List<Object> views = array(root.get("bufferViews"), "bufferViews");
        List<Object> accessors = array(root.get("accessors"), "accessors");
        List<Object> meshes = array(root.get("meshes"), "meshes");
        List<Object> materials = array(root.get("materials"), "materials");
        List<Object> nodes = array(root.get("nodes"), "nodes");
        List<String> warnings = new ArrayList<>();
        List<String> extensions = stringArray(root.get("extensionsUsed"));
        if (!extensions.isEmpty()) warnings.add("Extensões glTF encontradas: " + join(extensions)
                + ". Extensões de compressão e animação não são executadas.");
        warnings.add("Texturas glTF são identificadas, mas o renderizador usa cores de material e não aplica mapas de textura.");
        warnings.add("Câmeras, animações, skins e morph targets não são renderizados.");

        MeshData.Builder builder = new MeshData.Builder();
        collectMaterials(materials, root, builder, warnings);
        int[] sourceVertices = {0};
        if (!nodes.isEmpty()) {
            List<Object> scenes = array(root.get("scenes"), "scenes");
            if (!scenes.isEmpty()) {
                int activeScene = (int) number(root.get("scene"), 0);
                if (activeScene < 0 || activeScene >= scenes.size()) throw new ModelFormatException("Cena ativa glTF fora do intervalo.");
                List<Object> roots = array(object(scenes.get(activeScene), "scene glTF").get("nodes"), "scene.nodes");
                for (Object node : roots) walkNode((int) number(node, -1), identity(), new boolean[nodes.size()],
                        nodes, meshes, materials, views, accessors, buffers, builder, warnings, sourceVertices, 0);
            } else {
                boolean[] child = new boolean[nodes.size()];
                for (Object value : nodes) for (Object childIndex : array(object(value, "node").get("children"), "node.children")) {
                    int i = (int) number(childIndex, -1);
                    if (i >= 0 && i < child.length) child[i] = true;
                }
                for (int i = 0; i < nodes.size(); i++) if (!child[i]) walkNode(i, identity(), new boolean[nodes.size()],
                        nodes, meshes, materials, views, accessors, buffers, builder, warnings, sourceVertices, 0);
            }
        } else {
            for (int mesh = 0; mesh < meshes.size(); mesh++) drawMesh(mesh, identity(), meshes, materials,
                    views, accessors, buffers, builder, warnings, sourceVertices);
        }
        if (builder.getTriangleCount() == 0) return nonRenderable(ModelFormat.GLTF,
                "Arquivo glTF analisado, mas não contém primitivas triangulares compatíveis para exibir.");
        builder.setSourceVertices(sourceVertices[0]);
        MeshData mesh = builder.build();
        return new ModelLoadResult(glbBinary == null ? ModelFormat.GLTF : ModelFormat.GLB, mesh,
                "Malha glTF 2.0 renderizada; veja os avisos sobre recursos ignorados.", warnings);
    }

    private void walkNode(int index, float[] parent, boolean[] path, List<Object> nodes, List<Object> meshes,
                          List<Object> materials, List<Object> views, List<Object> accessors, List<byte[]> buffers,
                          MeshData.Builder builder, List<String> warnings, int[] sourceVertices, int depth)
            throws ModelFormatException {
        if (index < 0 || index >= nodes.size()) throw new ModelFormatException("Índice de nó glTF inválido.");
        if (depth > 64 || path[index]) throw new ModelFormatException("Hierarquia de nós glTF cíclica ou profunda demais.");
        path[index] = true;
        Map<String, Object> node = object(nodes.get(index), "node glTF");
        float[] world = multiply(parent, nodeTransform(node));
        if (node.containsKey("mesh")) drawMesh((int) number(node.get("mesh"), -1), world, meshes, materials,
                views, accessors, buffers, builder, warnings, sourceVertices);
        for (Object child : array(node.get("children"), "node.children")) {
            walkNode((int) number(child, -1), world, path, nodes, meshes, materials,
                    views, accessors, buffers, builder, warnings, sourceVertices, depth + 1);
        }
        path[index] = false;
    }

    private void drawMesh(int meshIndex, float[] transform, List<Object> meshes, List<Object> materials,
                          List<Object> views, List<Object> accessors, List<byte[]> buffers,
                          MeshData.Builder builder, List<String> warnings, int[] sourceVertices)
            throws ModelFormatException {
        if (meshIndex < 0 || meshIndex >= meshes.size()) throw new ModelFormatException("Índice de malha glTF inválido.");
        List<Object> primitives = array(object(meshes.get(meshIndex), "mesh glTF").get("primitives"), "mesh.primitives");
        for (Object value : primitives) {
            Map<String, Object> primitive = object(value, "primitive glTF");
            if (number(primitive.get("mode"), 4) != 4) {
                warnings.add("Uma primitiva glTF que não usa triângulos foi ignorada.");
                continue;
            }
            Map<String, Object> attributes = object(primitive.get("attributes"), "primitive.attributes");
            if (!attributes.containsKey("POSITION")) {
                warnings.add("Primitiva glTF sem atributo POSITION foi ignorada.");
                continue;
            }
            float[] positions = readPositions((int) number(attributes.get("POSITION"), -1), accessors, views, buffers);
            sourceVertices[0] += positions.length / 3;
            int[] indices;
            if (primitive.containsKey("indices")) indices = readIndices((int) number(primitive.get("indices"), -1), accessors, views, buffers);
            else {
                indices = new int[positions.length / 3];
                for (int i = 0; i < indices.length; i++) indices[i] = i;
            }
            if (indices.length % 3 != 0) throw new ModelFormatException("Índices glTF não formam triângulos completos.");
            float[] color = materialColor((int) number(primitive.get("material"), -1), materials);
            int materialIndex = (int) number(primitive.get("material"), -1);
            if (materialIndex >= 0 && materialIndex < materials.size()) {
                String name = string(object(materials.get(materialIndex), "material").get("name"));
                builder.addMaterial(name == null ? "Material " + materialIndex : name);
            }
            for (int i = 0; i < indices.length; i += 3) {
                float[] a = point(positions, indices[i], transform);
                float[] b = point(positions, indices[i + 1], transform);
                float[] c = point(positions, indices[i + 2], transform);
                builder.addTriangle(a, b, c, color);
            }
        }
    }

    private float[] readPositions(int accessorIndex, List<Object> accessors, List<Object> views, List<byte[]> buffers)
            throws ModelFormatException {
        Map<String, Object> accessor = get(accessors, accessorIndex, "accessor POSITION");
        if (number(accessor.get("componentType"), -1) != 5126 || !"VEC3".equals(string(accessor.get("type")))
                || accessor.containsKey("sparse")) throw new ModelFormatException("POSITION precisa ser um accessor VEC3 float não esparso.");
        int count = checkedCount(accessor);
        ByteBuffer data = accessorBuffer(accessor, views, buffers, 12);
        int stride = accessorStride(accessor, views, 12);
        int offset = (int) number(accessor.get("byteOffset"), 0);
        if ((long) offset + (long) Math.max(0, count - 1) * stride + 12 > data.limit())
            throw new ModelFormatException("Accessor POSITION sai dos limites do buffer.");
        float[] result = new float[count * 3];
        for (int i = 0; i < count; i++) {
            int p = offset + i * stride;
            for (int axis = 0; axis < 3; axis++) {
                float component = data.getFloat(p + axis * 4);
                if (!Float.isFinite(component)) throw new ModelFormatException("POSITION glTF contém valor não finito.");
                result[i * 3 + axis] = component;
            }
        }
        return result;
    }

    private int[] readIndices(int accessorIndex, List<Object> accessors, List<Object> views, List<byte[]> buffers)
            throws ModelFormatException {
        Map<String, Object> accessor = get(accessors, accessorIndex, "accessor indices");
        int component = (int) number(accessor.get("componentType"), -1);
        int bytes = component == 5121 ? 1 : component == 5123 ? 2 : component == 5125 ? 4 : 0;
        if (bytes == 0 || !"SCALAR".equals(string(accessor.get("type"))) || accessor.containsKey("sparse"))
            throw new ModelFormatException("Accessor de índices usa tipo não suportado.");
        int count = checkedCount(accessor);
        ByteBuffer data = accessorBuffer(accessor, views, buffers, bytes);
        int stride = accessorStride(accessor, views, bytes);
        int offset = (int) number(accessor.get("byteOffset"), 0);
        if ((long) offset + (long) Math.max(0, count - 1) * stride + bytes > data.limit())
            throw new ModelFormatException("Accessor de índices sai dos limites do buffer.");
        int[] result = new int[count];
        for (int i = 0; i < count; i++) {
            int p = offset + i * stride;
            long index = component == 5121 ? data.get(p) & 255L : component == 5123 ? data.getShort(p) & 65535L : data.getInt(p) & 0xffffffffL;
            if (index > Integer.MAX_VALUE) throw new ModelFormatException("Índice glTF excede o limite permitido.");
            result[i] = (int) index;
        }
        return result;
    }

    private static ByteBuffer accessorBuffer(Map<String, Object> accessor, List<Object> views, List<byte[]> buffers, int componentBytes)
            throws ModelFormatException {
        int viewIndex = (int) number(accessor.get("bufferView"), -1);
        Map<String, Object> view = get(views, viewIndex, "bufferView");
        int bufferIndex = (int) number(view.get("buffer"), 0);
        if (bufferIndex < 0 || bufferIndex >= buffers.size()) throw new ModelFormatException("Índice de buffer glTF inválido.");
        byte[] bytes = buffers.get(bufferIndex);
        long start = integer(view.get("byteOffset"), 0), length = integer(view.get("byteLength"), -1);
        if (start < 0 || length < 0 || start + length > bytes.length) throw new ModelFormatException("bufferView fora dos limites do buffer.");
        return ByteBuffer.wrap(bytes, (int) start, (int) length).slice().order(ByteOrder.LITTLE_ENDIAN);
    }
    private static int accessorStride(Map<String, Object> accessor, List<Object> views, int elementSize) throws ModelFormatException {
        Map<String, Object> view = get(views, (int) number(accessor.get("bufferView"), -1), "bufferView");
        int stride = (int) number(view.get("byteStride"), elementSize);
        if (stride < elementSize || stride > 252) throw new ModelFormatException("byteStride glTF inválido.");
        return stride;
    }
    private static int checkedCount(Map<String, Object> accessor) throws ModelFormatException {
        long count = integer(accessor.get("count"), -1);
        if (count < 1 || count > 200_000) throw new ModelFormatException("Accessor glTF vazio ou acima de 200 mil itens.");
        return (int) count;
    }

    private static void collectMaterials(List<Object> materials, Map<String, Object> root,
                                         MeshData.Builder builder, List<String> warnings) throws ModelFormatException {
        List<Object> textures = array(root.get("textures"), "textures");
        List<Object> images = array(root.get("images"), "images");
        for (int i = 0; i < materials.size(); i++) {
            Map<String, Object> material = object(materials.get(i), "material glTF");
            String name = string(material.get("name"));
            builder.addMaterial(name == null ? "Material " + i : name);
            Map<String, Object> pbr = optionalObject(material.get("pbrMetallicRoughness"));
            Map<String, Object> textureInfo = optionalObject(pbr.get("baseColorTexture"));
            if (textureInfo.containsKey("index")) {
                int textureIndex = (int) number(textureInfo.get("index"), -1);
                Map<String, Object> texture = get(textures, textureIndex, "texture");
                int imageIndex = (int) number(texture.get("source"), -1);
                Map<String, Object> image = get(images, imageIndex, "image");
                String imageName = string(image.get("uri"));
                if (imageName == null) imageName = string(image.get("name"));
                if (imageName == null) imageName = "imagem incorporada " + imageIndex;
                builder.addTexture(imageName);
            }
        }
    }
    private static float[] materialColor(int materialIndex, List<Object> materials) throws ModelFormatException {
        float[] color = {0.66f, 0.72f, 0.62f, 1f};
        if (materialIndex < 0) return color;
        Map<String, Object> material = get(materials, materialIndex, "material");
        Map<String, Object> pbr = optionalObject(material.get("pbrMetallicRoughness"));
        List<Object> factor = array(pbr.get("baseColorFactor"), "baseColorFactor");
        if (factor.size() == 4) for (int i = 0; i < 4; i++) {
            double component = number(factor.get(i), -1);
            if (component < 0 || component > 1) throw new ModelFormatException("baseColorFactor fora do intervalo [0,1].");
            color[i] = (float) component;
        }
        return color;
    }

    private static float[] nodeTransform(Map<String, Object> node) throws ModelFormatException {
        List<Object> matrix = array(node.get("matrix"), "node.matrix");
        if (!matrix.isEmpty()) {
            if (matrix.size() != 16) throw new ModelFormatException("Matriz de nó glTF precisa ter 16 valores.");
            float[] result = new float[16];
            for (int i = 0; i < 16; i++) result[i] = finiteNumber(matrix.get(i));
            return result;
        }
        float[] translation = vector(node.get("translation"), new float[]{0, 0, 0}, 3);
        float[] scale = vector(node.get("scale"), new float[]{1, 1, 1}, 3);
        float[] rotation = vector(node.get("rotation"), new float[]{0, 0, 0, 1}, 4);
        float x = rotation[0], y = rotation[1], z = rotation[2], w = rotation[3];
        float norm = (float) Math.sqrt(x*x + y*y + z*z + w*w);
        if (norm == 0) throw new ModelFormatException("Quaternion de nó glTF inválido.");
        x /= norm; y /= norm; z /= norm; w /= norm;
        float[] t = identity(); t[12] = translation[0]; t[13] = translation[1]; t[14] = translation[2];
        float[] r = identity();
        r[0] = 1-2*(y*y+z*z); r[1] = 2*(x*y+z*w); r[2] = 2*(x*z-y*w);
        r[4] = 2*(x*y-z*w); r[5] = 1-2*(x*x+z*z); r[6] = 2*(y*z+x*w);
        r[8] = 2*(x*z+y*w); r[9] = 2*(y*z-x*w); r[10] = 1-2*(x*x+y*y);
        float[] s = identity(); s[0] = scale[0]; s[5] = scale[1]; s[10] = scale[2];
        return multiply(multiply(t, r), s);
    }
    private static float[] vector(Object source, float[] fallback, int length) throws ModelFormatException {
        List<Object> values = array(source, "vetor glTF");
        if (values.isEmpty()) return fallback;
        if (values.size() != length) throw new ModelFormatException("Vetor de transformação glTF inválido.");
        float[] result = new float[length];
        for (int i = 0; i < length; i++) result[i] = finiteNumber(values.get(i));
        return result;
    }
    private static float finiteNumber(Object value) throws ModelFormatException {
        double number = number(value, Double.NaN);
        if (!Double.isFinite(number) || Math.abs(number) > Float.MAX_VALUE) throw new ModelFormatException("Transformação glTF não finita.");
        return (float) number;
    }
    private static float[] identity() {
        float[] m = new float[16]; m[0] = m[5] = m[10] = m[15] = 1; return m;
    }
    private static float[] multiply(float[] a, float[] b) {
        float[] result = new float[16];
        for (int col = 0; col < 4; col++) for (int row = 0; row < 4; row++)
            for (int k = 0; k < 4; k++) result[col * 4 + row] += a[k * 4 + row] * b[col * 4 + k];
        return result;
    }
    private static float[] point(float[] positions, int index, float[] transform) throws ModelFormatException {
        if (index < 0 || index * 3L + 2 >= positions.length) throw new ModelFormatException("Índice glTF fora dos vértices POSITION.");
        float x = positions[index * 3], y = positions[index * 3 + 1], z = positions[index * 3 + 2];
        float w = transform[3] * x + transform[7] * y + transform[11] * z + transform[15];
        if (w == 0 || !Float.isFinite(w)) throw new ModelFormatException("Transformação glTF produziu coordenada inválida.");
        return new float[]{(transform[0]*x + transform[4]*y + transform[8]*z + transform[12]) / w,
                (transform[1]*x + transform[5]*y + transform[9]*z + transform[13]) / w,
                (transform[2]*x + transform[6]*y + transform[10]*z + transform[14]) / w};
    }

    private static byte[] decodeDataUri(String uri) throws ModelFormatException {
        int comma = uri.indexOf(',');
        if (comma < 0 || !uri.substring(0, comma).endsWith(";base64"))
            throw new ModelFormatException("Somente buffers glTF em data URI Base64 podem ser lidos.");
        String data = uri.substring(comma + 1);
        if ((long) data.length() * 3 / 4 > MAX_TOTAL_BUFFER_BYTES + 3L) throw new ModelFormatException("Buffer Base64 glTF acima de 32 MiB.");
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        ByteBuffer output = ByteBuffer.allocate(data.length() * 3 / 4 + 3);
        int accumulator = 0, bits = 0;
        for (int i = 0; i < data.length(); i++) {
            char ch = data.charAt(i);
            if (ch == '=') break;
            int digit = alphabet.indexOf(ch);
            if (digit < 0) {
                if (Character.isWhitespace(ch)) continue;
                throw new ModelFormatException("Caracteres inválidos no buffer Base64 glTF.");
            }
            accumulator = (accumulator << 6) | digit;
            bits += 6;
            if (bits >= 8) { bits -= 8; output.put((byte) (accumulator >>> bits)); }
        }
        return Arrays.copyOf(output.array(), output.position());
    }

    private static ModelLoadResult nonRenderable(ModelFormat format, String message) {
        return new ModelLoadResult(format, null, message, Collections.emptyList());
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value, String name) throws ModelFormatException {
        if (!(value instanceof Map)) throw new ModelFormatException("Objeto " + name + " ausente ou inválido.");
        return (Map<String, Object>) value;
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> optionalObject(Object value) throws ModelFormatException {
        return value == null ? Collections.emptyMap() : object(value, "glTF");
    }
    @SuppressWarnings("unchecked") private static List<Object> array(Object value, String name) throws ModelFormatException {
        if (value == null) return Collections.emptyList();
        if (!(value instanceof List)) throw new ModelFormatException("Lista " + name + " inválida.");
        return (List<Object>) value;
    }
    private static List<String> stringArray(Object value) throws ModelFormatException {
        List<String> result = new ArrayList<>();
        for (Object item : array(value, "extensionsUsed")) {
            if (item instanceof String) result.add((String) item);
        }
        return result;
    }
    private static Map<String, Object> get(List<Object> values, int index, String name) throws ModelFormatException {
        if (index < 0 || index >= values.size()) throw new ModelFormatException("Índice " + name + " fora do intervalo.");
        return object(values.get(index), name);
    }
    private static String string(Object value) { return value instanceof String ? (String) value : null; }
    private static double number(Object value, double fallback) {
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }
    private static long integer(Object value, long fallback) throws ModelFormatException {
        double parsed = number(value, Double.NaN);
        if (!Double.isFinite(parsed) || parsed != Math.rint(parsed) || parsed < Long.MIN_VALUE || parsed > Long.MAX_VALUE)
            throw new ModelFormatException("Campo inteiro glTF inválido.");
        return (long) parsed;
    }
    private static String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(", ");
            result.append(value);
        }
        return result.toString();
    }
    private static long u32(byte[] b, int p) {
        return b[p] & 255L | (b[p + 1] & 255L) << 8 | (b[p + 2] & 255L) << 16 | (b[p + 3] & 255L) << 24;
    }
}
