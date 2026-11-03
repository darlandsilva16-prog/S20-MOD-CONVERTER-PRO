package com.fs20modconverterpro.conversion;

import android.app.Activity;
import android.app.AlertDialog;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tela independente para avaliação de conversibilidade e referências de mods. */
public final class ModConverterActivity extends Activity {
    private static final int OPEN_MOD = 750;
    private static final int SAVE_DIAGNOSTIC = 751;
    private static final int MAX_UI_REPORT = 60_000;
    private static final int MAX_EXPORT_REPORT = 1_000_000;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final PythonBridge bridge = new PythonBridge();
    private TextView status;
    private TextView report;
    private ProgressBar progress;
    private Button export;
    private Button exportReportButton;
    private Button convertedSection, incompatibleSection, pendingSection;
    private Button foundSection, missingSection, unverifiableSection, invalidSection, duplicateSection;
    private JSONObject lastReport;
    private String selectedName, lastExportText;
    private Uri lastSourceUri;

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
        convertedSection = sectionButton(root, "CONVERTIDOS", "converted");
        incompatibleSection = sectionButton(root, "INCOMPATÍVEIS", "incompatible");
        pendingSection = sectionButton(root, "PENDENTES", "pending");
        LinearLayout resourcesRow = new LinearLayout(this);
        resourcesRow.setOrientation(LinearLayout.HORIZONTAL);
        foundSection = categoryButton(resourcesRow, "ENCONTRADOS", "found");
        missingSection = categoryButton(resourcesRow, "AUSENTES", "missing");
        unverifiableSection = categoryButton(resourcesRow, "NÃO VERIFICÁVEIS", "unverifiable");
        root.addView(resourcesRow);
        LinearLayout issuesRow = new LinearLayout(this);
        issuesRow.setOrientation(LinearLayout.HORIZONTAL);
        invalidSection = categoryButton(issuesRow, "CAMINHOS/IDs INVÁLIDOS", "invalid");
        duplicateSection = categoryButton(issuesRow, "REPETIÇÕES", "duplicates");
        root.addView(issuesRow);
        exportReportButton = new Button(this);
        exportReportButton.setText("EXPORTAR DIAGNÓSTICO TXT");
        exportReportButton.setEnabled(false);
        exportReportButton.setOnClickListener(v -> chooseDiagnosticDestination());
        root.addView(exportReportButton);
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
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == SAVE_DIAGNOSTIC) {
            saveDiagnostic(uri);
            return;
        }
        if (request != OPEN_MOD) return;
        lastSourceUri = uri;
        selectedName = getDisplayName(uri);
        lastReport = null;
        lastExportText = null;
        exportReportButton.setEnabled(false);
        String filename = selectedName;
        status.setText("Lendo ZIP e analisando referências em segundo plano…");
        progress.setVisibility(View.VISIBLE);
        worker.execute(() -> {
            try {
                byte[] bytes = readBounded(uri);
                String json = bridge.assessMod(filename == null ? "mod.zip" : filename, bytes);
                JSONObject resultJson = new JSONObject(json);
                String fullText = format(resultJson, MAX_EXPORT_REPORT);
                String displayText = format(resultJson, MAX_UI_REPORT);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    progress.setVisibility(View.GONE);
                    lastReport = resultJson;
                    lastExportText = fullText;
                    updateSectionCounts(resultJson);
                    report.setText(displayText);
                    exportReportButton.setEnabled(true);
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

    private String format(JSONObject value, int maxLength) throws Exception {
        StringBuilder out = new StringBuilder();
        out.append("ESTADO: ").append(value.optString("status")).append('\n');
        out.append("DESTINO: ").append(value.optString("target")).append("\n\n");
        JSONObject totals = value.optJSONObject("totals");
        out.append("CONVERTIDOS (").append(totals == null ? 0 : totals.optInt("converted")).append(")\n")
                .append("INCOMPATÍVEIS (").append(totals == null ? 0 : totals.optInt("incompatible")).append(")\n")
                .append("PENDENTES (").append(totals == null ? 0 : totals.optInt("pending")).append(")\n\n")
                .append("Nenhuma transformação foi executada; o ZIP original permanece intacto.\n");
        JSONObject inventory = value.optJSONObject("inventory");
        if (inventory != null) {
            out.append("\nINVENTÁRIO\nArquivos: ").append(inventory.optInt("files"))
                    .append(" · I3D: ").append(inventory.optInt("i3d_files"))
                    .append(" · SHAPES: ").append(inventory.optInt("shapes_files"))
                    .append(" · texturas reconhecidas: ").append(inventory.optInt("texture_files")).append('\n');
            JSONObject extensions = inventory.optJSONObject("extensions");
            if (extensions != null) out.append("Extensões: ").append(extensions).append('\n');
            out.append("Detalhes por categoria limitados a ")
                    .append(inventory.optInt("detail_limit_per_category", 1000)).append(" itens.\n");
        }
        JSONObject counts = value.optJSONObject("totals");
        if (counts != null) out.append("Recursos: encontrados ").append(counts.optInt("resources_found"))
                .append(" · ausentes ").append(counts.optInt("resources_missing"))
                .append(" · não verificáveis ").append(counts.optInt("resources_unverifiable"))
                .append(" · caminhos/IDs inválidos ").append(counts.optInt("resources_invalid"))
                .append(" · referências repetidas ").append(counts.optInt("duplicate_references"))
                .append(" · IDs repetidos ").append(counts.optInt("duplicate_identifiers")).append('\n');

        JSONArray i3dInventory = value.optJSONArray("i3d_inventory");
        out.append("\nINVENTÁRIO I3D\n");
        if (i3dInventory == null || i3dInventory.length() == 0) out.append("Nenhum I3D encontrado.\n");
        else for (int i = 0; i < i3dInventory.length(); i++) {
            JSONObject item = i3dInventory.optJSONObject(i);
            if (item != null) out.append("• ").append(item.optString("path"))
                    .append(" [").append(item.optString("status")).append("] · modelos/elementos ")
                    .append(item.optInt("models")).append(" · materiais ").append(item.optInt("materials"))
                    .append(" · referências ").append(item.optInt("references"))
                    .append(item.optString("message").isEmpty() ? "" : " · " + item.optString("message"))
                    .append('\n');
        }

        JSONArray shapesInventory = value.optJSONArray("shapes_inventory");
        out.append("\nCABEÇALHOS SHAPES (sem decodificação do conteúdo)\n");
        if (shapesInventory != null) for (int i = 0; i < shapesInventory.length(); i++) {
            JSONObject shape = shapesInventory.optJSONObject(i);
            if (shape != null) out.append("• ").append(shape.optString("path"))
                    .append(" [").append(shape.optString("status")).append("] · bytes ")
                    .append(shape.optString("header_hex", "não disponíveis"))
                    .append(shape.has("version_candidate") ? " · candidato de versão " + shape.optInt("version_candidate") : "")
                    .append(" · payload lido/decodificado: não\n");
        }

        JSONObject groups = value.optJSONObject("resource_groups");
        if (groups != null) {
            appendResourceGroup(out, "RECURSOS ENCONTRADOS", groups.optJSONArray("found"), counts == null ? 0 : counts.optInt("resources_found"));
            appendResourceGroup(out, "RECURSOS AUSENTES", groups.optJSONArray("missing"), counts == null ? 0 : counts.optInt("resources_missing"));
            appendResourceGroup(out, "RECURSOS NÃO VERIFICÁVEIS", groups.optJSONArray("unverifiable"), counts == null ? 0 : counts.optInt("resources_unverifiable"));
            appendResourceGroup(out, "CAMINHOS OU IDs INVÁLIDOS/AMBÍGUOS", groups.optJSONArray("invalid"), counts == null ? 0 : counts.optInt("resources_invalid"));
        }
        appendResourceGroup(out, "REFERÊNCIAS REPETIDAS", value.optJSONArray("duplicate_references"),
                counts == null ? 0 : counts.optInt("duplicate_references"));
        appendResourceGroup(out, "IDENTIFICADORES REPETIDOS", value.optJSONArray("duplicate_identifiers"),
                counts == null ? 0 : counts.optInt("duplicate_identifiers"));

        JSONArray scenes = value.optJSONArray("i3d");
        out.append("\nDEPENDÊNCIAS POR MODELO/SHAPE (sem inferir renderização)\n");
        if (scenes == null || scenes.length() == 0) out.append("Nenhum arquivo .i3d encontrado.\n");
        else for (int i = 0; i < scenes.length(); i++) {
            JSONObject scene = scenes.optJSONObject(i);
            if (scene == null) continue;
            out.append("• ").append(scene.optString("i3d")).append('\n');
            JSONArray models = scene.optJSONArray("model_dependencies");
            if (models != null) for (int j = 0; j < models.length(); j++) {
                JSONObject model = models.optJSONObject(j);
                if (model == null) continue;
                out.append("  ").append(model.optString("element", "shape/model"))
                        .append(" ID ").append(model.optString("shape_id"));
                if (!model.optString("name").isEmpty()) out.append(" · ").append(model.optString("name"));
                out.append(" · materialIds ").append(model.optJSONArray("material_ids")).append('\n');
                JSONArray missingMaterials = model.optJSONArray("missing_material_ids");
                if (missingMaterials != null && missingMaterials.length() > 0)
                    out.append("    IDs de material sem definição: ").append(missingMaterials).append('\n');
                JSONArray dependencies = model.optJSONArray("dependencies");
                if (dependencies != null) for (int k = 0; k < dependencies.length(); k++) {
                    JSONObject dependency = dependencies.optJSONObject(k);
                    if (dependency != null) {
                        out.append("    - ").append(dependency.optString("type"))
                                .append(dependency.has("material_id") ? " material " + dependency.optString("material_id") : "")
                                .append(dependency.has("map") ? " mapa " + dependency.optString("map") : "")
                                .append(": ").append(dependency.optString("reference"))
                                .append(" → ").append(dependency.optString("resolved_path", "não resolvido"))
                                .append(" [").append(dependency.optString("status", "NÃO VERIFICÁVEL")).append("]")
                                .append(dependency.optString("issue").isEmpty() ? "" : " (" + dependency.optString("issue") + ")");
                        JSONObject header = dependency.optJSONObject("shapes_header");
                        if (header != null) out.append(" · cabeçalho SHAPES observado ")
                                .append(header.optString("header_hex"))
                                .append(header.has("version_candidate")
                                        ? " · versão candidata " + header.optInt("version_candidate") : "")
                                .append(" · payload não decodificado");
                        out.append('\n');
                    }
                }
            }
            JSONArray duplicates = scene.optJSONArray("duplicate_identifiers");
            if (duplicates != null && duplicates.length() > 0)
                out.append("  IDs repetidos: ").append(duplicates).append('\n');
        }
        out.append("\nEXPORTAÇÃO\n").append(value.optString("message"));
        if (out.length() > maxLength) return out.substring(0, maxLength) + "\n… relatório limitado pelo tamanho seguro.";
        return out.toString();
    }

    private void appendResourceGroup(StringBuilder out, String title, JSONArray rows, int total) {
        int shown = rows == null ? 0 : rows.length();
        out.append('\n').append(title).append(" (").append(total).append(")");
        if (shown < total) out.append(" · mostrando ").append(shown);
        out.append('\n');
        if (rows == null) return;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            out.append("• ").append(row.optString("i3d", ""));
            if (row.has("i3d")) out.append(" → ");
            out.append(row.optString("reference", row.optString("name", "")))
                    .append(" [").append(row.optString("status", "" )).append("]");
            String target = row.optString("resolved_path", "");
            if (!target.isEmpty() && !"null".equals(target)) out.append(" → ").append(target);
            if (row.has("occurrences")) out.append(" · ocorrências ").append(row.optInt("occurrences"));
            if (!row.optString("issue").isEmpty()) out.append(" · ").append(row.optString("issue"));
            if (!row.optString("detail").isEmpty()) out.append(" · ").append(row.optString("detail"));
            out.append('\n');
        }
    }

    private Button sectionButton(LinearLayout root, String label, String key) {
        Button button = new Button(this);
        button.setText(label + " (0) — VER DETALHES");
        button.setOnClickListener(v -> showSection(key));
        root.addView(button);
        return button;
    }

    private Button categoryButton(LinearLayout row, String label, String key) {
        Button button = new Button(this);
        button.setText(label + " (0)");
        button.setTextSize(11);
        button.setOnClickListener(v -> showSection(key));
        row.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        return button;
    }

    private void updateSectionCounts(JSONObject result) {
        JSONObject totals = result.optJSONObject("totals");
        if (totals == null) return;
        convertedSection.setText("CONVERTIDOS (" + totals.optInt("converted") + ") — VER DETALHES");
        incompatibleSection.setText("INCOMPATÍVEIS (" + totals.optInt("incompatible") + ") — VER DETALHES");
        pendingSection.setText("PENDENTES (" + totals.optInt("pending") + ") — VER DETALHES");
        foundSection.setText("ENCONTRADOS (" + totals.optInt("resources_found") + ")");
        missingSection.setText("AUSENTES (" + totals.optInt("resources_missing") + ")");
        unverifiableSection.setText("NÃO VERIF. (" + totals.optInt("resources_unverifiable") + ")");
        invalidSection.setText("INVÁLIDOS (" + totals.optInt("resources_invalid") + ")");
        duplicateSection.setText("REPETIÇÕES (" + (totals.optInt("duplicate_references")
                + totals.optInt("duplicate_identifiers")) + ")");
    }

    private void showSection(String key) {
        if (lastReport == null) return;
        JSONArray items;
        if ("duplicates".equals(key)) {
            items = new JSONArray();
            JSONArray refs = lastReport.optJSONArray("duplicate_references");
            JSONArray ids = lastReport.optJSONArray("duplicate_identifiers");
            copyItems(items, refs);
            copyItems(items, ids);
        } else if ("converted".equals(key) || "incompatible".equals(key) || "pending".equals(key)) {
            items = lastReport.optJSONArray(key);
        } else {
            JSONObject groups = lastReport.optJSONObject("resource_groups");
            items = groups == null ? null : groups.optJSONArray(key);
        }
        StringBuilder details = new StringBuilder();
        if ("converted".equals(key) && (items == null || items.length() == 0)) {
            details.append("Nenhuma conversão foi executada.");
        } else if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null) details.append("• ").append(item.optString("i3d"))
                        .append(item.has("i3d") ? " → " : "")
                        .append(item.optString("reference", item.optString("name")))
                        .append(" [").append(item.optString("status")).append("]")
                        .append(item.optString("resolved_path").isEmpty() ? "" : "\n  " + item.optString("resolved_path"))
                        .append(item.optString("detail").isEmpty() ? "" : "\n  " + item.optString("detail"))
                        .append(item.optString("issue").isEmpty() ? "" : "\n  " + item.optString("issue"))
                        .append("\n\n");
            }
        }
        String title = "converted".equals(key) ? "CONVERTIDOS"
                : "incompatible".equals(key) ? "INCOMPATÍVEIS"
                : "pending".equals(key) ? "PENDENTES"
                : "found".equals(key) ? "RECURSOS ENCONTRADOS"
                : "missing".equals(key) ? "RECURSOS AUSENTES"
                : "unverifiable".equals(key) ? "RECURSOS NÃO VERIFICÁVEIS"
                : "invalid".equals(key) ? "CAMINHOS/IDs INVÁLIDOS"
                : "REFERÊNCIAS/IDENTIFICADORES REPETIDOS";
        new AlertDialog.Builder(this).setTitle(title).setMessage(details.toString())
                .setPositiveButton("FECHAR", null).show();
    }

    private void copyItems(JSONArray target, JSONArray source) {
        if (source == null) return;
        for (int i = 0; i < source.length(); i++) target.put(source.optJSONObject(i));
    }

    private void chooseDiagnosticDestination() {
        if (lastExportText == null) return;
        String base = selectedName == null ? "mod" : selectedName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        base = base.replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.isEmpty()) base = "mod";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "diagnostico-i3d-" + base + ".txt");
        try { startActivityForResult(intent, SAVE_DIAGNOSTIC); }
        catch (ActivityNotFoundException error) { status.setText("Seletor Android para salvar TXT indisponível."); }
    }

    private void saveDiagnostic(Uri destination) {
        if (lastExportText == null) {
            status.setText("Não há diagnóstico disponível para salvar.");
            return;
        }
        if (destination.equals(lastSourceUri)) {
            status.setText("Destino recusado: o ZIP original não pode ser sobrescrito.");
            return;
        }
        status.setText("Salvando diagnóstico TXT…");
        String text = lastExportText;
        worker.execute(() -> {
            try (OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) throw new IOException("Não foi possível abrir o destino TXT.");
                output.write(text.getBytes(StandardCharsets.UTF_8));
                output.flush();
                runOnUiThread(() -> status.setText("Diagnóstico TXT salvo. O ZIP original permanece intacto."));
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("Falha ao salvar TXT: " + message(error)));
            }
        });
    }

    private String message(Throwable error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
    private void showError(String detail) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            progress.setVisibility(View.GONE);
            exportReportButton.setEnabled(false);
            status.setText(detail);
            report.setText("O ZIP não foi convertido nem modificado.\n" + detail);
        });
    }

    @Override protected void onDestroy() { super.onDestroy(); worker.shutdownNow(); }
}
