package com.fs20modconverterpro.gar;

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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Tela isolada do GAR ENGINE PRO; só abre e examina o prefixo do documento. */
public final class GarEngineActivity extends Activity {
    private static final int OPEN_DOCUMENT = 931;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final GarPythonBridge bridge = new GarPythonBridge();
    private TextView moduleState, selectedFile, report, status;
    private Button openButton, cancelButton;
    private ProgressBar progress;
    private volatile InputStream activeInput;
    private Future<?> currentTask;
    private volatile int analysisGeneration;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    private void buildUi() {
        ScrollView page = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 16, 20, 16);
        page.addView(root);
        addText(root, "GAR ENGINE PRO", 24);
        addText(root, "Análise somente para leitura dos primeiros 128 bytes. A identificação do cabeçalho não valida o índice GAR; a leitura do índice ainda não está disponível.", 15);
        moduleState = addText(root, "Estado do módulo: aguardando inicialização sob demanda", 15);
        selectedFile = addText(root, "Arquivo selecionado: nenhum", 15);
        openButton = new Button(this);
        openButton.setText("ABRIR ARQUIVO GAR");
        openButton.setOnClickListener(v -> openDocument());
        root.addView(openButton);
        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        root.addView(progress);
        cancelButton = new Button(this);
        cancelButton.setText("CANCELAR ANÁLISE");
        cancelButton.setVisibility(View.GONE);
        cancelButton.setOnClickListener(v -> cancelAnalysis());
        root.addView(cancelButton);
        report = addText(root, "Resultado da análise aparecerá aqui.", 14);
        status = addText(root, "Aviso: não são executados scripts do GAR, mods Lua ou código Python recebido.", 14);
        addText(root, "Avisos de segurança: leitura máxima de 128 bytes; sem descriptografia, extração, reconstrução ou pesquisa interna. Os dados numéricos não recebem significado presumido.", 14);
        setContentView(page);
    }

    private TextView addText(LinearLayout root, String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setPadding(4, 8, 4, 8);
        view.setTextIsSelectable(true);
        root.addView(view);
        return view;
    }

    private void openDocument() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(intent, OPEN_DOCUMENT); }
        catch (ActivityNotFoundException error) { status.setText("Seletor de arquivos Android indisponível."); }
    }

    @Override protected void onActivityResult(int request, int resultCode, Intent data) {
        super.onActivityResult(request, resultCode, data);
        if (request != OPEN_DOCUMENT || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        analyze(data.getData());
    }

    private void analyze(Uri uri) {
        int generation = ++analysisGeneration;
        openButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        cancelButton.setVisibility(View.VISIBLE);
        moduleState.setText("Estado do módulo: iniciando GAR ENGINE PRO sob demanda…");
        status.setText("Lendo somente o cabeçalho em segundo plano; o original não será escrito.");
        currentTask = worker.submit(() -> {
            try {
                String filename = queryName(uri);
                long fileSize = querySize(uri);
                byte[] prefix = readPrefix(uri);
                String json = bridge.analyzeHeader(getApplicationContext(), filename, fileSize, prefix);
                JSONObject result = new JSONObject(json);
                String formatted = formatReport(result);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || generation != analysisGeneration) return;
                    selectedFile.setText("Arquivo selecionado: " + filename);
                    moduleState.setText("Estado do módulo: GAR ENGINE PRO carregado; análise somente para leitura concluída");
                    report.setText(formatted);
                    status.setText("Análise concluída. Nenhuma operação de escrita ou execução foi realizada.");
                    finishBusyState();
                });
            } catch (OutOfMemoryError error) {
                showFailure(generation, "Memória insuficiente para ler o cabeçalho.");
            } catch (Exception error) {
                showFailure(generation, error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage());
            }
        });
    }

    private byte[] readPrefix(Uri uri) throws IOException {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("O seletor não conseguiu abrir o arquivo.");
            activeInput = input;
            ByteArrayOutputStream output = new ByteArrayOutputStream(GarPythonBridge.MAX_HEADER_BYTES);
            byte[] buffer = new byte[GarPythonBridge.MAX_HEADER_BYTES];
            while (output.size() < buffer.length && !Thread.currentThread().isInterrupted()) {
                int count = input.read(buffer, 0, Math.min(buffer.length - output.size(), 32));
                if (count < 0) break;
                if (count == 0) {
                    int one = input.read();
                    if (one < 0) break;
                    output.write(one);
                } else output.write(buffer, 0, count);
            }
            if (Thread.currentThread().isInterrupted()) throw new IOException("Análise cancelada.");
            return output.toByteArray();
        } finally { activeInput = null; }
    }

    private String queryName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0 && cursor.getString(column) != null) return cursor.getString(column);
            }
        } catch (Exception ignored) { }
        return "arquivo.gar";
    }

    private long querySize(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (column >= 0 && !cursor.isNull(column)) return cursor.getLong(column);
            }
        } catch (Exception ignored) { }
        return -1;
    }

    private String formatReport(JSONObject value) {
        StringBuilder out = new StringBuilder();
        out.append("Módulo: ").append(value.optString("module")).append('\n')
                .append("Estado da análise: ").append(value.optString("state"))
                .append(" (").append(value.optString("state_code")).append(")\n")
                .append("Tamanho total: ").append(value.optLong("size_bytes", -1) < 0
                        ? "não informado" : value.optLong("size_bytes") + " bytes").append('\n')
                .append("Bytes lidos: ").append(value.optInt("header_bytes_read")).append(" / 128\n")
                .append("Banner GIANTS: ").append(value.optBoolean("giants_banner_found")
                        ? "posição " + value.optInt("giants_banner_offset") : "não encontrado").append('\n')
                .append("Assinatura GAR: ").append(value.optBoolean("gar_signature_found")
                        ? "posição " + value.optInt("gar_signature_offset") + " · " + value.optString("gar_signature_hex")
                        : "não encontrada").append('\n')
                .append("Leitura do índice: ").append(value.optBoolean("index_read_available")
                        ? "disponível" : "ainda não disponível").append('\n')
                .append("Estado do índice: ").append(value.optString("index_state")).append('\n')
                .append("Versão: não confirmada\nQuantidade de arquivos: não confirmada\n")
                .append("Valores binários observados (sem significado atribuído):\n");
        JSONArray fields = value.optJSONArray("observed_fields");
        if (fields == null || fields.length() == 0) out.append("- Nenhum campo completo encontrado.\n");
        else for (int i = 0; i < fields.length(); i++) {
            JSONObject field = fields.optJSONObject(i);
            if (field != null) out.append("- ").append(field.optString("label"))
                    .append(": ").append(field.optLong("observed_value"))
                    .append(" (uint").append(field.optInt("width_bits"))
                    .append(" LE; bytes ").append(field.optString("raw_hex")).append(")\n");
        }
        out.append("Bytes brutos do prefixo (máximo 128): ").append(value.optString("header_hex")).append('\n')
                .append("\nOperações disponíveis:\nLeitura: sim\nConteúdo validado: não\nPesquisa: não\nExtração: não\nReconstrução: não\n\nAvisos:\n");
        JSONArray warnings = value.optJSONArray("warnings");
        if (warnings != null) for (int i = 0; i < warnings.length(); i++) out.append("• ").append(warnings.optString(i)).append('\n');
        return out.toString();
    }

    private void cancelAnalysis() {
        analysisGeneration++;
        Future<?> task = currentTask;
        if (task != null) task.cancel(true);
        InputStream input = activeInput;
        if (input != null) try { input.close(); } catch (IOException ignored) { }
        status.setText("Análise cancelada. Nenhum dado do arquivo foi gravado.");
        finishBusyState();
    }

    private void showFailure(int generation, String message) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || generation != analysisGeneration) return;
            moduleState.setText("Estado do módulo: falha na inicialização ou análise");
            status.setText("Análise não concluída: " + message);
            finishBusyState();
        });
    }

    private void finishBusyState() {
        progress.setVisibility(View.GONE);
        cancelButton.setVisibility(View.GONE);
        openButton.setEnabled(true);
    }

    @Override protected void onDestroy() {
        analysisGeneration++;
        super.onDestroy();
        InputStream input = activeInput;
        if (input != null) try { input.close(); } catch (IOException ignored) { }
        worker.shutdownNow();
    }
}
