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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Identifica o cabeçalho GAR e exporta somente o relatório, sem ler o índice proprietário. */
public final class GarManagerActivity extends Activity {
    private static final int REQUEST_OPEN = 901;
    private static final int REQUEST_SAVE_REPORT = 902;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final GarInspector inspector = new GarInspector();
    private ProgressBar progress;
    private Button cancelButton, saveReportButton, openButton;
    private TextView reportView, statusView;
    private EditText searchField;
    private Future<?> runningTask;
    private volatile InputStream activeInput;
    private String lastReport;
    private String selectedName = "arquivo.gar";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
    }

    private void buildUi() {
        ScrollView page = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 16, 20, 16);
        page.addView(root);

        TextView title = text("GERENCIADOR GAR PRO", 24);
        root.addView(title);
        root.addView(text("Identificação segura do cabeçalho. GAR não será tratado como ZIP e nenhum conteúdo protegido será decifrado.", 15));

        openButton = button("ABRIR GAR");
        openButton.setOnClickListener(v -> openGar());
        root.addView(openButton);

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        root.addView(progress);
        cancelButton = button("CANCELAR LEITURA");
        cancelButton.setVisibility(View.GONE);
        cancelButton.setOnClickListener(v -> cancelCurrentTask());
        root.addView(cancelButton);

        searchField = new EditText(this);
        searchField.setSingleLine(true);
        searchField.setHint("Pesquisar arquivos internos");
        searchField.setEnabled(false);
        root.addView(searchField);
        Button searchButton = button("PESQUISAR ARQUIVOS");
        searchButton.setEnabled(false);
        root.addView(searchButton);

        Button selectButton = button("SELECIONAR ARQUIVOS E PASTAS");
        selectButton.setEnabled(false);
        root.addView(selectButton);
        Button extractAllButton = button("EXTRAIR TUDO");
        extractAllButton.setEnabled(false);
        root.addView(extractAllButton);
        Button extractSelectedButton = button("EXTRAIR SELECIONADOS");
        extractSelectedButton.setEnabled(false);
        root.addView(extractSelectedButton);
        Button rebuildButton = button("RECONSTRUIR GAR");
        rebuildButton.setEnabled(false);
        root.addView(rebuildButton);
        root.addView(text("Pesquisa, seleção, extração e reconstrução permanecem desativadas enquanto nenhum índice GAR validado estiver disponível.", 14));

        reportView = text("Nenhum arquivo analisado. Selecione um .gar para consultar o cabeçalho.", 15);
        root.addView(reportView);
        saveReportButton = button("SALVAR RELATÓRIO DE IDENTIFICAÇÃO");
        saveReportButton.setEnabled(false);
        saveReportButton.setOnClickListener(v -> chooseReportDestination());
        root.addView(saveReportButton);
        statusView = text("Extração e reconstrução indisponíveis para os formatos GAR encontrados.", 14);
        root.addView(statusView);
        setContentView(page);
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setPadding(4, 9, 4, 9);
        view.setTextIsSelectable(true);
        return view;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        return button;
    }

    private void openGar() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(intent, REQUEST_OPEN); }
        catch (ActivityNotFoundException error) { statusView.setText("Seletor Android de arquivos indisponível."); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) {
            if (request == REQUEST_OPEN || request == REQUEST_SAVE_REPORT)
                statusView.setText("Operação cancelada; o arquivo original não foi alterado.");
            return;
        }
        if (request == REQUEST_OPEN) inspect(data.getData());
        else if (request == REQUEST_SAVE_REPORT) saveReport(data.getData());
    }

    private void inspect(Uri uri) {
        selectedName = displayName(uri);
        String filename = selectedName;
        long sourceSize = querySize(uri);
        lastReport = null;
        saveReportButton.setEnabled(false);
        openButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        cancelButton.setVisibility(View.VISIBLE);
        statusView.setText("Lendo somente os primeiros " + GarInspector.HEADER_LIMIT + " bytes…");
        runningTask = worker.submit(() -> {
            try {
                InputStream source = getContentResolver().openInputStream(uri);
                if (source == null) throw new IOException("Não foi possível abrir o documento.");
                activeInput = source;
                GarInspector.Result result;
                try (InputStream input = source) {
                    result = inspector.inspect(filename, sourceSize, input);
                } finally {
                    activeInput = null;
                }
                String text = result.toReport();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    reportView.setText(text);
                    lastReport = text;
                    saveReportButton.setEnabled(true);
                    openButton.setEnabled(true);
                    progress.setVisibility(View.GONE);
                    cancelButton.setVisibility(View.GONE);
                    statusView.setText(result.state + ". " + result.message);
                });
            } catch (java.io.InterruptedIOException cancelled) {
                showCancelled();
            } catch (Exception error) {
                showFailure("Falha ao ler o cabeçalho: " + safeMessage(error));
            }
        });
    }

    private void cancelCurrentTask() {
        Future<?> task = runningTask;
        if (task != null) task.cancel(true);
        InputStream input = activeInput;
        if (input != null) {
            try { input.close(); } catch (IOException ignored) { }
        }
        showCancelled();
    }

    private void showCancelled() {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            progress.setVisibility(View.GONE);
            cancelButton.setVisibility(View.GONE);
            openButton.setEnabled(true);
            statusView.setText("Leitura cancelada. Nenhum conteúdo foi extraído e o original permanece intacto.");
        });
    }

    private void showFailure(String reason) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            progress.setVisibility(View.GONE);
            cancelButton.setVisibility(View.GONE);
            openButton.setEnabled(true);
            statusView.setText(reason);
        });
    }

    private void chooseReportDestination() {
        if (lastReport == null) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "relatorio-gar-" + safeBaseName(selectedName) + ".txt");
        try { startActivityForResult(intent, REQUEST_SAVE_REPORT); }
        catch (ActivityNotFoundException error) { statusView.setText("Seletor Android para salvar relatório indisponível."); }
    }

    private void saveReport(Uri destination) {
        String content = lastReport;
        if (content == null) return;
        progress.setVisibility(View.VISIBLE);
        cancelButton.setVisibility(View.GONE);
        openButton.setEnabled(false);
        statusView.setText("Salvando somente o relatório em um novo documento…");
        runningTask = worker.submit(() -> {
            try (OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) throw new IOException("O provedor não criou o relatório.");
                output.write(content.getBytes(StandardCharsets.UTF_8));
                output.flush();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    progress.setVisibility(View.GONE);
                    cancelButton.setVisibility(View.GONE);
                    openButton.setEnabled(true);
                    statusView.setText("Relatório de identificação salvo. O GAR original não foi escrito.");
                });
            } catch (Exception error) { showFailure("Não foi possível salvar o relatório: " + safeMessage(error)); }
        });
    }

    private String displayName(Uri uri) {
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

    private String safeBaseName(String value) {
        String name = value == null ? "arquivo" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        return name.toLowerCase(java.util.Locale.ROOT).endsWith(".gar")
                ? name.substring(0, name.length() - 4) : name;
    }

    private String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        InputStream input = activeInput;
        if (input != null) {
            try { input.close(); } catch (IOException ignored) { }
        }
        worker.shutdownNow();
    }
}
