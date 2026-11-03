package com.fs20modconverterpro.conversion;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.fs20modconverterpro.python.PythonBridge;
import com.fs20modconverterpro.viewer3d.GiantsShapesDecoder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Tela independente para avaliação de conversibilidade e referências de mods. */
public final class ModConverterActivity extends Activity {
    private static final int OPEN_MOD = 750;
    private static final int MAX_UI_REPORT = 60_000;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final PythonBridge bridge = new PythonBridge();
    private TextView status;
    private TextView report;
    private ProgressBar progress;
    private Button export;
    private String selectedName;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        worker.execute(() -> {
            try { bridge.start(this); }
            catch (Throwable error) { runOnUiThread(() -> status.setText("Motor de análise indisponível: " + message(error))); }
        });
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 16, 20, 16);
        TextView title = label("CONVERSOR REAL DE MODS", 22);
        root.addView(title);
        TextView note = label("Avalia um ZIP sem alterar o original. A tela informa o que foi relacionado e o que ainda impede uma conversão validada para FS20.", 15);
        root.addView(note);
        Button open = new Button(this);
        open.setText("ABRIR MOD ZIP PARA AVALIAÇÃO");
        open.setOnClickListener(v -> openMod());
        root.addView(open);
        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        root.addView(progress);
        ScrollView scroll = new ScrollView(this);
        report = label("Nenhum relatório. Selecione um ZIP para analisar arquivos I3D, SHAPES, materiais, texturas e metadados.", 14);
        scroll.addView(report);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        status = label("Nenhuma conversão executada.", 14);
        root.addView(status);
        export = new Button(this);
        export.setText("EXPORTAÇÃO BLOQUEADA — SEM TRANSFORMAÇÕES VALIDADAS");
        export.setEnabled(false);
        root.addView(export);
        setContentView(root);
    }

    private TextView label(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setPadding(4, 8, 4, 8);
        view.setTextIsSelectable(true);
        return view;
    }

    private void openMod() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(intent, OPEN_MOD); }
        catch (ActivityNotFoundException error) { status.setText("Seletor de arquivos Android indisponível."); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != OPEN_MOD || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        selectedName = getDisplayName(uri);
        String filename = selectedName;
        status.setText("Lendo ZIP e analisando referências em segundo plano…");
        progress.setVisibility(View.VISIBLE);
        worker.execute(() -> {
            try {
                byte[] bytes = readBounded(uri);
                String json = bridge.assessMod(filename == null ? "mod.zip" : filename, bytes);
                JSONObject resultJson = new JSONObject(json);
                attachDecodedShapes(bytes, resultJson);
                String text = format(resultJson);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    progress.setVisibility(View.GONE);
                    report.setText(text);
                    status.setText("Avaliação concluída para " + (filename == null ? "o ZIP" : filename)
                            + ". Nenhum arquivo do mod foi escrito ou executado.");
                });
            } catch (OutOfMemoryError error) { showError("Memória insuficiente para avaliar este ZIP."); }
            catch (Throwable error) { showError("Falha na avaliação: " + message(error)); }
        });
    }

    private byte[] readBounded(Uri uri) throws Exception {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalArgumentException("Não foi possível abrir o ZIP escolhido.");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if ((long) out.size() + count > PythonBridge.MAX_INPUT_BYTES)
                    throw new IllegalArgumentException("O ZIP excede o limite seguro de 32 MiB.");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    private String getDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0 && cursor.getString(index) != null) return cursor.getString(index);
            }
        } catch (Exception ignored) { }
        return "mod.zip";
    }

    private String format(JSONObject value) throws Exception {
        StringBuilder out = new StringBuilder();
        out.append("ESTADO: ").append(value.optString("status")).append('\n');
        out.append("DESTINO: ").append(value.optString("target")).append("\n\n");
        out.append("CONVERTIDOS (0)\nNenhuma transformação de formato foi realizada.\n\n");
        appendItems(out, "INCOMPATÍVEIS", value.optJSONArray("incompatible"));
        appendItems(out, "PENDENTES", value.optJSONArray("pending"));
        JSONArray scenes = value.optJSONArray("i3d");
        out.append("\nRELAÇÕES I3D\n");
        if (scenes == null || scenes.length() == 0) out.append("Nenhum arquivo .i3d encontrado.\n");
        else for (int i = 0; i < scenes.length(); i++) {
            JSONObject scene = scenes.optJSONObject(i);
            if (scene == null) continue;
            out.append("• ").append(scene.optString("i3d")).append('\n');
            JSONArray shapeIds = scene.optJSONArray("shape_ids");
            out.append("  IDs de forma na cena: ");
            if (shapeIds == null || shapeIds.length() == 0) out.append("nenhum identificado");
            else for (int j = 0; j < shapeIds.length(); j++) {
                if (j > 0) out.append(", ");
                out.append(shapeIds.optString(j));
            }
            out.append('\n');
            JSONArray sceneShapes = scene.optJSONArray("scene_shapes");
            if (sceneShapes != null) for (int j = 0; j < sceneShapes.length(); j++) {
                JSONObject shape = sceneShapes.optJSONObject(j);
                if (shape != null) out.append("  Forma I3D ").append(shape.optString("shape_id"))
                        .append(" usa materialIds ").append(shape.optJSONArray("material_ids")).append('\n');
            }
            JSONArray decodedShapes = scene.optJSONArray("decoded_shapes");
            if (decodedShapes != null) for (int j = 0; j < decodedShapes.length(); j++) {
                JSONObject shape = decodedShapes.optJSONObject(j);
                if (shape != null) out.append("  Forma binária relacionada: ID ")
                        .append(shape.optString("id")).append(" · ")
                        .append(shape.optString("name")).append(" · ")
                        .append(shape.optInt("vertices")).append(" vértices · ")
                        .append(shape.optInt("faces")).append(" faces\n");
            }
            if (scene.has("shapes_version"))
                out.append("  Versão binária SHAPES: ").append(scene.optInt("shapes_version")).append('\n');
            JSONArray missingShapes = scene.optJSONArray("unmatched_shape_ids");
            if (missingShapes != null && missingShapes.length() > 0)
                out.append("  IDs de forma sem correspondência no SHAPES: ").append(missingShapes).append('\n');
            if (scene.has("shape_decode_warning"))
                out.append("  Aviso SHAPES: ").append(scene.optString("shape_decode_warning")).append('\n');
            JSONArray materials = scene.optJSONArray("materials");
            if (materials != null) for (int j = 0; j < materials.length(); j++) {
                JSONObject material = materials.optJSONObject(j);
                if (material == null) continue;
                out.append("  Material ").append(material.optString("id")).append(':');
                JSONArray maps = material.optJSONArray("maps");
                if (maps == null || maps.length() == 0) out.append(" sem mapa de textura identificado");
                else for (int k = 0; k < maps.length(); k++) {
                    JSONObject map = maps.optJSONObject(k);
                    if (map != null) out.append("\n    ").append(map.optString("map"))
                            .append(" [fileId ").append(map.optString("file_id")).append("]: ")
                            .append(map.optString("resolved_path", "não relacionado"));
                }
                out.append('\n');
            }
            JSONArray refs = scene.optJSONArray("references");
            if (refs != null) for (int j = 0; j < refs.length() && j < 500; j++) {
                JSONObject ref = refs.optJSONObject(j);
                if (ref != null) out.append("  - ").append(ref.optString("type")).append(": ")
                        .append(ref.optString("reference")).append(" → ")
                        .append(ref.optString("resolved_path", "não localizado"))
                        .append(ref.optString("issue").isEmpty() ? "" : " (" + ref.optString("issue") + ")")
                        .append('\n');
            }
        }
        out.append("\nEXPORTAÇÃO\n").append(value.optString("message"));
        if (out.length() > MAX_UI_REPORT) return out.substring(0, MAX_UI_REPORT) + "\n… relatório visual limitado.";
        return out.toString();
    }

    /** Relaciona IDs I3D com IDs reais do binário SHAPES, sem gravar nem alterar o ZIP. */
    private void attachDecodedShapes(byte[] zipBytes, JSONObject report) {
        Map<String, List<JSONObject>> scenesByShapes = new HashMap<>();
        Map<String, Set<String>> shapesByScene = new HashMap<>();
        Map<String, JSONObject> sceneByPath = new HashMap<>();
        JSONArray scenes = report.optJSONArray("i3d");
        if (scenes == null) return;
        for (int i = 0; i < scenes.length(); i++) {
            JSONObject scene = scenes.optJSONObject(i);
            if (scene == null) continue;
            String scenePath = scene.optString("i3d", "");
            sceneByPath.put(scenePath, scene);
            JSONArray refs = scene.optJSONArray("references");
            if (refs == null) continue;
            for (int j = 0; j < refs.length(); j++) {
                JSONObject ref = refs.optJSONObject(j);
                if (ref == null || !"geometria".equals(ref.optString("type"))) continue;
                String path = ref.optString("resolved_path", "");
                if (!path.isEmpty() && !"null".equals(path)) {
                    Set<String> shapePaths = shapesByScene.get(scenePath);
                    if (shapePaths == null) {
                        shapePaths = new HashSet<>();
                        shapesByScene.put(scenePath, shapePaths);
                    }
                    shapePaths.add(path);
                    List<JSONObject> related = scenesByShapes.get(path);
                    if (related == null) {
                        related = new ArrayList<>();
                        scenesByShapes.put(path, related);
                    }
                    if (!related.contains(scene)) related.add(scene);
                }
            }
        }
        for (Map.Entry<String, Set<String>> entry : shapesByScene.entrySet()) {
            if (entry.getValue().size() > 1) {
                JSONObject scene = sceneByPath.get(entry.getKey());
                try { if (scene != null) scene.put("shape_decode_warning", "A cena aponta para mais de um arquivo SHAPES; a associação automática dos IDs foi bloqueada por ambiguidade."); }
                catch (Exception ignored) { }
            }
        }
        Map<String, List<JSONObject>> unambiguousScenes = new HashMap<>();
        for (Map.Entry<String, List<JSONObject>> entry : scenesByShapes.entrySet()) {
            List<JSONObject> related = new ArrayList<>();
            for (JSONObject scene : entry.getValue()) {
                String scenePath = scene.optString("i3d", "");
                Set<String> paths = shapesByScene.get(scenePath);
                if (paths != null && paths.size() == 1) related.add(scene);
            }
            if (!related.isEmpty()) unambiguousScenes.put(entry.getKey(), related);
        }
        scenesByShapes = unambiguousScenes;
        if (scenesByShapes.isEmpty()) return;
        Set<String> visited = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String path = entry.getName().replace('\\', '/');
                List<JSONObject> matching = scenesByShapes.get(path);
                if (matching == null || entry.isDirectory() || !visited.add(path)) {
                    zip.closeEntry();
                    continue;
                }
                byte[] shapeBytes = readBounded(zip, GiantsShapesDecoder.MAX_BYTES);
                try {
                    GiantsShapesDecoder.ShapesFile decoded = new GiantsShapesDecoder().decode(shapeBytes);
                    for (JSONObject scene : matching) addShapeMatches(scene, decoded);
                } catch (Exception exception) {
                    for (JSONObject scene : matching) {
                        try { scene.put("shape_decode_warning", safeReason(exception)); }
                        catch (Exception ignored) { }
                    }
                }
                zip.closeEntry();
            }
        } catch (Exception exception) {
            for (List<JSONObject> matching : scenesByShapes.values())
                for (JSONObject scene : matching) {
                    try { scene.put("shape_decode_warning", "Leitura do ZIP/SHAPES falhou: " + safeReason(exception)); }
                    catch (Exception ignored) { }
                }
        }
    }

    private void addShapeMatches(JSONObject scene, GiantsShapesDecoder.ShapesFile decoded) throws Exception {
        JSONArray ids = scene.optJSONArray("shape_ids");
        JSONArray matches = new JSONArray();
        JSONArray missing = new JSONArray();
        Map<Long, GiantsShapesDecoder.Shape> byId = new HashMap<>();
        for (GiantsShapesDecoder.Shape shape : decoded.shapes) byId.put(shape.id, shape);
        if (ids != null) for (int i = 0; i < ids.length(); i++) {
            String value = ids.optString(i);
            try {
                long id = Long.parseLong(value);
                GiantsShapesDecoder.Shape shape = byId.get(id);
                if (shape == null) missing.put(value);
                else {
                    JSONObject row = new JSONObject();
                    row.put("id", shape.id);
                    row.put("name", shape.name);
                    row.put("vertices", shape.positions.length);
                    row.put("faces", shape.indices.length / 3);
                    matches.put(row);
                }
            } catch (NumberFormatException ignored) { missing.put(value); }
        }
        scene.put("decoded_shapes", matches);
        scene.put("unmatched_shape_ids", missing);
        scene.put("shapes_version", decoded.version);
    }

    private byte[] readBounded(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if ((long) out.size() + count > maxBytes)
                throw new IOException("SHAPES excede 32 MiB.");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    private String safeReason(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private void appendItems(StringBuilder out, String heading, JSONArray items) {
        out.append(heading).append(" (").append(items == null ? 0 : items.length()).append(")\n");
        if (items == null || items.length() == 0) { out.append("Nenhum item.\n\n"); return; }
        for (int i = 0; i < items.length() && i < 1000; i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null) out.append("• ").append(item.optString("name"))
                    .append(" — ").append(item.optString("detail")).append('\n');
        }
        out.append('\n');
    }

    private String message(Throwable error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
    private void showError(String detail) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            progress.setVisibility(View.GONE);
            status.setText(detail);
            report.setText("O ZIP não foi convertido nem modificado.\n" + detail);
        });
    }

    @Override protected void onDestroy() { super.onDestroy(); worker.shutdownNow(); }
}
