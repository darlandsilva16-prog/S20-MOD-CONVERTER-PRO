package com.fs20modconverterpro.python;

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

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Painel do runtime Python incorporado ao APK. */
public final class PythonEngineActivity extends Activity {
    private static final int OPEN_FILE = 881;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final PythonBridge bridge = new PythonBridge();
    private final ArrayDeque<String> history = new ArrayDeque<>();
    private TextView state, details, status;
    private ProgressBar progress;
    private String runtimeSummary = "Estado: runtime Python ainda não inicializado.";
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        buildUi();
        setState("INICIALIZANDO");
        progress.setVisibility(View.VISIBLE);
        worker.execute(() -> {
            try {
                bridge.start(this);
                JSONObject info = new JSONObject(bridge.runtimeInfo());
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    progress.setVisibility(View.GONE);
                    setState(info.optString("state", "PRONTO"));
                    StringBuilder text = new StringBuilder("Versão Python: ").append(info.optString("python", "não informada"));
                    text.append("\nImplementação: ").append(info.optString("implementation", "não informada"));
                    text.append("\n\nBibliotecas/módulos locais:");
                    org.json.JSONArray libraries = info.optJSONArray("libraries");
                    if (libraries != null) for (int i = 0; i < libraries.length(); i++) text.append("\n• ").append(libraries.optString(i));
                    JSONObject modules = info.optJSONObject("modules");
                    if (modules != null) for (Iterator<String> it = modules.keys(); it.hasNext();) {
                        String key = it.next(); text.append("\n• ").append(key).append(" — ").append(modules.optString(key));
                    }
                    runtimeSummary = text.toString();
                    details.setText(runtimeSummary + historySection());
                    status.setText("Runtime Python iniciado dentro do APK.");
                });
            } catch (Throwable error) { showError("Falha ao iniciar Python: " + safeMessage(error)); }
        });
    }
    private void buildUi() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(18, 14, 18, 14);
        TextView heading = label("MOTOR PYTHON PRO", 22); root.addView(heading);
        state = label("Estado: aguardando", 16); root.addView(state);
        Button open = new Button(this); open.setText("ANALISAR ARQUIVO"); open.setOnClickListener(v -> openFile()); root.addView(open);
        progress = new ProgressBar(this); progress.setVisibility(View.GONE); root.addView(progress);
        ScrollView scroll = new ScrollView(this); details = label("Carregando runtime e módulos disponíveis…", 14); scroll.addView(details);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        status = label("Nenhum arquivo processado.", 13); root.addView(status); setContentView(root);
    }
    private TextView label(String text, int size) { TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setPadding(4, 8, 4, 8); return view; }
    private void setState(String value) { state.setText("Estado do motor Python: " + value); }
    private void openFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("*/*"); intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(intent, OPEN_FILE); } catch (ActivityNotFoundException e) { status.setText("Seletor Android indisponível."); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != OPEN_FILE || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData(); String filename = getName(uri); progress.setVisibility(View.VISIBLE); status.setText("Processando em segundo plano: " + filename);
        worker.execute(() -> {
            try {
                byte[] bytes = readBounded(uri);
                String json = bridge.analyze(filename, bytes);
                JSONObject report = new JSONObject(json);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    progress.setVisibility(View.GONE); addHistory("OK · " + filename);
                    details.setText(runtimeSummary + "\n\nÚltimo resultado:\n" + formatReport(report) + historySection());
                    status.setText("Análise concluída; o original foi aberto somente para leitura.");
                });
            } catch (OutOfMemoryError error) { showError("Memória insuficiente para analisar este arquivo."); }
            catch (Throwable error) { showError("Erro ao processar " + filename + ": " + safeMessage(error)); }
        });
    }
    private String getName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) { int col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (col >= 0) return cursor.getString(col); }
        } catch (Exception ignored) { }
        return "arquivo";
    }
    private byte[] readBounded(Uri uri) throws Exception {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalArgumentException("Não foi possível abrir o documento.");
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
            while ((n = input.read(buffer)) != -1) { if ((long) output.size() + n > PythonBridge.MAX_INPUT_BYTES) throw new IllegalArgumentException("Arquivo acima de 32 MiB."); output.write(buffer, 0, n); }
            return output.toByteArray();
        }
    }
    private void addHistory(String item) { history.addFirst(item); while (history.size() > 10) history.removeLast(); }
    private String historySection() { StringBuilder out = new StringBuilder("\n\nHistórico recente:"); if (history.isEmpty()) return out.append("\nNenhuma operação registrada.").toString(); for (String entry : history) out.append("\n• ").append(entry); return out.toString(); }
    private String formatReport(JSONObject report) {
        StringBuilder out = new StringBuilder();
        String[] keys = {"file", "size_bytes", "format", "status", "message", "version", "seed", "width", "height", "elements", "entries", "unsafe_entries", "expanded_bytes", "omitted_entries", "omitted_unsafe_entries", "shapes_references", "errors", "fs_diagnostic"};
        String[] labels = {"Arquivo", "Tamanho em bytes", "Formato", "Estado", "Mensagem", "Versão binária", "Semente", "Largura", "Altura", "Elementos XML", "Itens ZIP", "Caminhos inseguros", "Bytes descompactados", "Itens omitidos no resumo", "Caminhos omitidos no resumo", "Referências de geometria", "Problemas estruturais", "Diagnóstico de versão do mod"};
        for (int i = 0; i < keys.length; i++) if (report.has(keys[i])) out.append(labels[i]).append(": ").append(formatValue(report.opt(keys[i]))).append('\n');
        return out.toString().trim();
    }
    private String formatValue(Object value) {
        if (value instanceof org.json.JSONArray) {
            org.json.JSONArray array = (org.json.JSONArray) value; StringBuilder out = new StringBuilder();
            for (int i = 0; i < array.length(); i++) { if (i > 0) out.append("\n"); Object item = array.opt(i); out.append("• ").append(formatValue(item)); }
            return out.toString();
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value; StringBuilder out = new StringBuilder();
            String[] fields = {"name", "size", "compressed", "line", "descVersion", "status", "message"};
            String[] names = {"Nome", "Tamanho", "Compactado", "Linha", "descVersion", "Estado", "Detalhe"};
            for (int i = 0; i < fields.length; i++) if (object.has(fields[i])) { if (out.length() > 0) out.append(" · "); out.append(names[i]).append("=").append(object.opt(fields[i])); }
            return out.length() == 0 ? object.toString() : out.toString();
        }
        return String.valueOf(value);
    }
    private String safeMessage(Throwable error) { String message = error.getMessage(); return message == null ? error.getClass().getSimpleName() : message; }
    private void showError(String message) { runOnUiThread(() -> { if (isFinishing() || isDestroyed()) return; progress.setVisibility(View.GONE); setState("ERRO"); addHistory("ERRO · " + message); details.setText(runtimeSummary + historySection()); status.setText(message); new AlertDialog.Builder(this).setTitle("Erro no Motor Python").setMessage(message).setPositiveButton("OK", null).show(); }); }
    @Override protected void onDestroy() { super.onDestroy(); worker.shutdownNow(); }
}
