package com.fs20modconverterpro.editor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.fs20modconverterpro.R;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Editor XML com abertura e exportação protegidas pelo seletor de documentos Android. */
public final class XmlEditorActivity extends Activity {
    private static final int REQUEST_OPEN_XML = 201;
    private static final int REQUEST_SAVE_XML = 202;
    private static final String[] XML_MIME_TYPES = {
            "application/xml",
            "text/xml",
            "application/xhtml+xml"
    };

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler mainThread = new Handler(Looper.getMainLooper());
    private final XmlProcessor processor = new XmlProcessor();
    private final UndoRedoHistory history = new UndoRedoHistory();
    private final Runnable refreshEditorRunnable = this::refreshEditor;

    private Button openButton;
    private Button undoButton;
    private Button redoButton;
    private Button formatButton;
    private Button validateButton;
    private Button saveAsButton;
    private EditText codeEditor;
    private TextView lineNumbers;
    private TextView fileNameView;
    private TextView statusView;
    private ProgressBar progressBar;

    private XmlDocumentCodec.DecodedXml decodedDocument;
    private Uri sourceUri;
    private String fileName;
    private String savedText = "";
    private String pendingSaveText;
    private byte[] pendingSaveBytes;
    private boolean ignoreHistory;
    private boolean busy;
    private int changeStart;
    private String removedText = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_xml_editor);

        openButton = (Button) findViewById(R.id.xml_abrir_button);
        undoButton = (Button) findViewById(R.id.xml_desfazer_button);
        redoButton = (Button) findViewById(R.id.xml_refazer_button);
        formatButton = (Button) findViewById(R.id.xml_formatar_button);
        validateButton = (Button) findViewById(R.id.xml_validar_button);
        saveAsButton = (Button) findViewById(R.id.xml_salvar_button);
        codeEditor = (EditText) findViewById(R.id.xml_codigo);
        lineNumbers = (TextView) findViewById(R.id.xml_line_numbers);
        fileNameView = (TextView) findViewById(R.id.xml_nome_arquivo);
        statusView = (TextView) findViewById(R.id.xml_status);
        progressBar = (ProgressBar) findViewById(R.id.xml_progresso);

        codeEditor.setFilters(new InputFilter[]{
                new InputFilter.LengthFilter(XmlDocumentCodec.MAX_XML_CHARACTERS)
        });
        codeEditor.setEnabled(false);
        openButton.setOnClickListener(view -> requestOpenXml());
        undoButton.setOnClickListener(view -> undo());
        redoButton.setOnClickListener(view -> redo());
        formatButton.setOnClickListener(view -> formatXml());
        validateButton.setOnClickListener(view -> validateXml());
        saveAsButton.setOnClickListener(view -> saveAs());
        codeEditor.addTextChangedListener(editorWatcher);
        updateButtons();
    }

    private final TextWatcher editorWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            changeStart = start;
            int safeEnd = Math.min(text.length(), start + count);
            removedText = text.subSequence(start, safeEnd).toString();
        }

        @Override
        public void onTextChanged(CharSequence text, int start, int before, int count) {
            insertedLength = count;
        }

        @Override
        public void afterTextChanged(Editable editable) {
            String inserted = changeStart <= editable.length()
                    ? editable.subSequence(changeStart, Math.min(editable.length(), changeStart + insertedLength)).toString()
                    : "";
            if (!ignoreHistory && decodedDocument != null) {
                history.recordEdit(changeStart, removedText, inserted, SystemClock.uptimeMillis());
            }
            scheduleRefreshEditor();
            updateButtons();
        }
    };

    private int insertedLength;

    private void requestOpenXml() {
        if (hasUnsavedChanges()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.xml_sair_titulo)
                    .setMessage(R.string.xml_sair_mensagem)
                    .setNegativeButton(R.string.xml_sair_continuar, null)
                    .setPositiveButton(R.string.xml_sair_descartar,
                            (dialog, which) -> launchOpenPicker())
                    .show();
        } else {
            launchOpenPicker();
        }
    }

    private void launchOpenPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, XML_MIME_TYPES);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_OPEN_XML);
            statusView.setText(R.string.selecione_xml);
        } catch (ActivityNotFoundException exception) {
            statusView.setText(R.string.erro_seletor_arquivo);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_OPEN_XML && requestCode != REQUEST_SAVE_XML) {
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            clearPendingSave();
            setBusy(false, R.string.operacao_cancelada);
            return;
        }
        if (requestCode == REQUEST_OPEN_XML) {
            readXml(data.getData());
        } else {
            writeXml(data.getData());
        }
    }

    private void readXml(Uri uri) {
        String selectedName = queryDisplayName(uri);
        String mimeType;
        try {
            mimeType = getContentResolver().getType(uri);
        } catch (RuntimeException exception) {
            mimeType = null;
        }
        if (!isXmlFile(selectedName, mimeType)) {
            statusView.setText(R.string.xml_nao_suportado);
            return;
        }
        long size = querySize(uri);
        if (size > XmlDocumentCodec.MAX_XML_BYTES) {
            statusView.setText(R.string.xml_tamanho_excedido);
            return;
        }

        setBusy(true, R.string.xml_carregando);
        worker.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) {
                    throw new IOException("Não foi possível abrir o documento.");
                }
                byte[] bytes = XmlDocumentCodec.readBounded(input);
                XmlDocumentCodec.DecodedXml decoded = XmlDocumentCodec.decode(bytes);
                mainThread.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    sourceUri = uri;
                    decodedDocument = decoded;
                    fileName = selectedName;
                    ignoreHistory = true;
                    codeEditor.setText(decoded.getText());
                    ignoreHistory = false;
                    history.clear();
                    savedText = decoded.getText();
                    fileNameView.setText(fileName);
                    codeEditor.setSelection(0);
                    codeEditor.setEnabled(true);
                    refreshEditor();
                    setBusy(false, getString(
                            R.string.xml_aberto,
                            fileName,
                            Formatter.formatFileSize(this, bytes.length),
                            decoded.getCharset().name()));
                });
            } catch (Exception exception) {
                mainThread.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    setBusy(false, R.string.xml_erro_abertura);
                });
            }
        });
    }

    private void validateXml() {
        if (decodedDocument == null || busy) {
            return;
        }
        String snapshot = codeEditor.getText().toString();
        setBusy(true, R.string.xml_validando);
        worker.execute(() -> {
            XmlValidationResult result = processor.validate(snapshot);
            mainThread.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                setBusy(false, result.isValid()
                        ? getString(R.string.xml_valido)
                        : result.getMessage());
            });
        });
    }

    private void formatXml() {
        if (decodedDocument == null || busy) {
            return;
        }
        String snapshot = codeEditor.getText().toString();
        int oldSelection = codeEditor.getSelectionStart();
        String encoding = decodedDocument.getCharset().name();
        setBusy(true, R.string.xml_formatando);
        worker.execute(() -> {
            try {
                String formatted = processor.format(snapshot, encoding);
                mainThread.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    boolean changed = !snapshot.equals(formatted);
                    if (changed) {
                        codeEditor.setText(formatted);
                        codeEditor.setSelection(Math.min(oldSelection, formatted.length()));
                    }
                    setBusy(false, changed
                            ? getString(R.string.xml_formatado)
                            : getString(R.string.xml_sem_alteracao));
                });
            } catch (XmlProcessor.XmlProcessingException exception) {
                mainThread.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    setBusy(false, exception.getMessage());
                });
            }
        });
    }

    private void saveAs() {
        if (decodedDocument == null || busy) {
            return;
        }
        String snapshot = codeEditor.getText().toString();
        pendingSaveText = snapshot;
        setBusy(true, R.string.xml_salvando_preparando);
        worker.execute(() -> {
            try {
                byte[] bytes = XmlDocumentCodec.encode(snapshot, decodedDocument);
                mainThread.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        clearPendingSave();
                        return;
                    }
                    pendingSaveBytes = bytes;
                    launchSavePicker();
                });
            } catch (IOException exception) {
                mainThread.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    clearPendingSave();
                    setBusy(false, exception.getMessage() != null
                            && exception.getMessage().contains("declaração")
                            ? getString(R.string.xml_erro_codificacao)
                            : getString(R.string.xml_operacao_falhou));
                });
            }
        });
    }

    private void launchSavePicker() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/xml");
        intent.putExtra(Intent.EXTRA_TITLE, xmlOutputName(fileName));
        try {
            startActivityForResult(intent, REQUEST_SAVE_XML);
            statusView.setText(R.string.xml_escolha_destino);
        } catch (ActivityNotFoundException exception) {
            clearPendingSave();
            setBusy(false, R.string.erro_seletor_arquivo);
        }
    }

    private void writeXml(Uri destinationUri) {
        byte[] bytes = pendingSaveBytes;
        String exportedText = pendingSaveText;
        if (bytes == null || exportedText == null) {
            setBusy(false, R.string.xml_operacao_falhou);
            return;
        }
        setBusy(true, R.string.xml_salvando);
        worker.execute(() -> {
            try (OutputStream output = getContentResolver().openOutputStream(destinationUri, "w")) {
                if (output == null) {
                    throw new IOException("Não foi possível abrir o destino.");
                }
                output.write(bytes);
                output.flush();
                mainThread.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    sourceUri = destinationUri;
                    fileName = queryDisplayName(destinationUri);
                    fileNameView.setText(fileName);
                    savedText = exportedText;
                    clearPendingSave();
                    setBusy(false, getString(
                            R.string.xml_salvo,
                            fileName,
                            Formatter.formatFileSize(this, bytes.length)));
                });
            } catch (Exception exception) {
                try {
                    DocumentsContract.deleteDocument(getContentResolver(), destinationUri);
                } catch (Exception ignored) {
                    // A cópia incompleta só é removida se o provedor permitir.
                }
                mainThread.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    clearPendingSave();
                    setBusy(false, R.string.xml_erro_salvar);
                });
            }
        });
    }

    private void undo() {
        UndoRedoHistory.Change change = history.undo(codeEditor.getText().toString());
        applyHistoryChange(change, R.string.xml_desfeita);
    }

    private void redo() {
        UndoRedoHistory.Change change = history.redo(codeEditor.getText().toString());
        applyHistoryChange(change, R.string.xml_refeita);
    }

    private void applyHistoryChange(UndoRedoHistory.Change change, int statusResource) {
        if (change == null) {
            statusView.setText(statusResource == R.string.xml_desfeita
                    ? R.string.xml_desfazer_indisponivel
                    : R.string.xml_refazer_indisponivel);
            return;
        }
        ignoreHistory = true;
        codeEditor.setText(change.getText());
        codeEditor.setSelection(Math.min(change.getSelection(), codeEditor.length()));
        ignoreHistory = false;
        scheduleRefreshEditor();
        setStatus(getString(statusResource));
        updateButtons();
    }

    private void scheduleRefreshEditor() {
        mainThread.removeCallbacks(refreshEditorRunnable);
        mainThread.postDelayed(refreshEditorRunnable, 120);
    }

    private void refreshEditor() {
        Editable editable = codeEditor.getText();
        lineNumbers.setText(buildLineNumbers(editable));
        new XmlSyntaxHighlighter(
                getColor(R.color.xml_tag),
                getColor(R.color.xml_atributo),
                getColor(R.color.xml_valor),
                getColor(R.color.xml_comentario),
                getColor(R.color.xml_declaracao),
                getColor(R.color.xml_entidade),
                getColor(R.color.xml_delimitador)).apply(editable);
        updateButtons();
    }

    private static String buildLineNumbers(CharSequence text) {
        int lines = 1;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n') {
                lines++;
            }
        }
        StringBuilder result = new StringBuilder(lines * 4);
        for (int line = 1; line <= lines; line++) {
            if (line > 1) {
                result.append('\n');
            }
            result.append(line);
        }
        return result.toString();
    }

    private void setBusy(boolean value, int messageResource) {
        setBusy(value, getString(messageResource));
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        progressBar.setVisibility(value ? View.VISIBLE : View.GONE);
        openButton.setEnabled(!value);
        codeEditor.setEnabled(!value && decodedDocument != null);
        formatButton.setEnabled(!value && decodedDocument != null);
        validateButton.setEnabled(!value && decodedDocument != null);
        saveAsButton.setEnabled(!value && decodedDocument != null);
        statusView.setText(message);
        updateButtons();
    }

    private void updateButtons() {
        boolean available = !busy && decodedDocument != null;
        undoButton.setEnabled(available && history.canUndo());
        redoButton.setEnabled(available && history.canRedo());
        formatButton.setEnabled(available);
        validateButton.setEnabled(available);
        saveAsButton.setEnabled(available);
        openButton.setEnabled(!busy);
        codeEditor.setEnabled(available);
    }

    private boolean hasUnsavedChanges() {
        return decodedDocument != null && !codeEditor.getText().toString().equals(savedText);
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0 && !TextUtils.isEmpty(cursor.getString(column))) {
                    return cursor.getString(column);
                }
            }
        } catch (RuntimeException ignored) {
            // O nome alternativo é obtido da URI quando o provedor não o informa.
        }
        String fallback = uri.getLastPathSegment();
        return TextUtils.isEmpty(fallback) ? getString(R.string.nome_arquivo_desconhecido) : fallback;
    }

    private long querySize(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (column >= 0 && !cursor.isNull(column)) {
                    return cursor.getLong(column);
                }
            }
        } catch (RuntimeException ignored) {
            // O tamanho também é limitado durante a leitura do fluxo.
        }
        return -1;
    }

    private static boolean isXmlFile(String name, String mimeType) {
        String lowerName = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        String lowerMime = mimeType == null ? "" : mimeType.toLowerCase(java.util.Locale.ROOT);
        return lowerName.endsWith(".xml")
                || "application/xml".equals(lowerMime)
                || "text/xml".equals(lowerMime)
                || "application/xhtml+xml".equals(lowerMime);
    }

    private static String xmlOutputName(String inputName) {
        String name = TextUtils.isEmpty(inputName) ? "mod.xml" : inputName;
        return name.toLowerCase(java.util.Locale.ROOT).endsWith(".xml") ? name : name + ".xml";
    }

    private void clearPendingSave() {
        pendingSaveBytes = null;
        pendingSaveText = null;
    }

    private void setStatus(String message) {
        statusView.setText(message);
    }

    @Override
    public void onBackPressed() {
        if (!hasUnsavedChanges()) {
            super.onBackPressed();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.xml_sair_titulo)
                .setMessage(R.string.xml_sair_mensagem)
                .setNegativeButton(R.string.xml_sair_continuar, null)
                .setPositiveButton(R.string.xml_sair_descartar, (dialog, which) -> finish())
                .show();
    }

    @Override
    protected void onDestroy() {
        mainThread.removeCallbacks(refreshEditorRunnable);
        worker.shutdownNow();
        super.onDestroy();
    }
}
