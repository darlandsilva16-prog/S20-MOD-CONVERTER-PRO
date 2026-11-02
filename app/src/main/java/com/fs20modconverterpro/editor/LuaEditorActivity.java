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
import android.text.InputFilter;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.fs20modconverterpro.R;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Editor Lua local: lê, analisa e exporta texto; nunca executa scripts. */
public final class LuaEditorActivity extends Activity {
    private static final int REQUEST_OPEN = 301;
    private static final int REQUEST_SAVE_AS = 302;
    private static final String[] LUA_MIME_TYPES = {
            "text/x-lua", "application/x-lua", "text/plain", "application/octet-stream"
    };
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler mainThread = new Handler(Looper.getMainLooper());
    private final LuaSyntaxAnalyzer analyzer = new LuaSyntaxAnalyzer();
    private final LuaFormatter formatter = new LuaFormatter();
    private final UndoRedoHistory history = new UndoRedoHistory();
    private final Runnable refreshRunnable = this::refreshEditor;

    private Button openButton, saveButton, saveAsButton, undoButton, redoButton;
    private Button formatButton, validateButton, findButton;
    private EditText codeEditor;
    private TextView lineNumbers, fileNameView, statusView;
    private ProgressBar progressBar;
    private LuaDocumentCodec.DecodedLua decoded;
    private Uri sourceUri;
    private String fileName, savedText = "", pendingText;
    private byte[] pendingBytes;
    private boolean ignoreHistory, busy;
    private int changedStart, insertedLength;
    private int editorLineCount = 1;
    private String removedText = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_lua_editor);
        openButton = (Button) findViewById(R.id.lua_abrir);
        saveButton = (Button) findViewById(R.id.lua_salvar);
        saveAsButton = (Button) findViewById(R.id.lua_salvar_como);
        undoButton = (Button) findViewById(R.id.lua_desfazer);
        redoButton = (Button) findViewById(R.id.lua_refazer);
        formatButton = (Button) findViewById(R.id.lua_formatar);
        validateButton = (Button) findViewById(R.id.lua_validar);
        findButton = (Button) findViewById(R.id.lua_localizar);
        codeEditor = (EditText) findViewById(R.id.lua_codigo);
        lineNumbers = (TextView) findViewById(R.id.lua_linhas);
        fileNameView = (TextView) findViewById(R.id.lua_nome);
        statusView = (TextView) findViewById(R.id.lua_status);
        progressBar = (ProgressBar) findViewById(R.id.lua_progresso);
        codeEditor.setFilters(new InputFilter[]{
                new InputFilter.LengthFilter(LuaDocumentCodec.MAX_TEXT_CHARACTERS),
                (source, start, end, dest, dstart, dend) -> {
                    int nextLines = editorLineCount - countLines(dest, dstart, dend)
                            + countLines(source, start, end);
                    return nextLines > LuaDocumentCodec.MAX_LINES ? "" : null;
                }
        });
        codeEditor.setEnabled(false);
        openButton.setOnClickListener(v -> requestOpen());
        saveButton.setOnClickListener(v -> save());
        saveAsButton.setOnClickListener(v -> saveAs());
        undoButton.setOnClickListener(v -> applyHistory(history.undo(codeEditor.getText().toString()), true));
        redoButton.setOnClickListener(v -> applyHistory(history.redo(codeEditor.getText().toString()), false));
        formatButton.setOnClickListener(v -> format());
        validateButton.setOnClickListener(v -> validate());
        findButton.setOnClickListener(v -> showFindReplace());
        codeEditor.addTextChangedListener(editorWatcher);
        updateButtons();
    }

    private final TextWatcher editorWatcher = new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            changedStart = start;
            removedText = text.subSequence(start, Math.min(text.length(), start + count)).toString();
        }
        @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
            insertedLength = count;
        }
        @Override public void afterTextChanged(Editable editable) {
            String inserted = editable.subSequence(changedStart,
                    Math.min(editable.length(), changedStart + insertedLength)).toString();
            editorLineCount += countLines(inserted, 0, inserted.length())
                    - countLines(removedText, 0, removedText.length());
            if (!ignoreHistory && decoded != null) {
                history.recordEdit(changedStart, removedText, inserted, SystemClock.uptimeMillis());
            }
            mainThread.removeCallbacks(refreshRunnable);
            mainThread.postDelayed(refreshRunnable, 150);
            updateButtons();
        }
    };

    private void requestOpen() {
        if (hasChanges()) {
            new AlertDialog.Builder(this).setTitle(R.string.lua_descartar_titulo)
                    .setMessage(R.string.lua_descartar_mensagem)
                    .setNegativeButton(R.string.lua_continuar, null)
                    .setPositiveButton(R.string.lua_descartar, (d, w) -> launchOpen()).show();
        } else launchOpen();
    }

    private void launchOpen() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, LUA_MIME_TYPES);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { startActivityForResult(intent, REQUEST_OPEN); }
        catch (ActivityNotFoundException e) { setStatus(getString(R.string.lua_seletor_indisponivel)); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_OPEN && requestCode != REQUEST_SAVE_AS) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            pendingBytes = null; pendingText = null;
            setBusy(false, getString(R.string.lua_cancelado));
            return;
        }
        if (requestCode == REQUEST_OPEN) open(data.getData()); else write(data.getData(), false);
    }

    private void open(Uri uri) {
        String selectedName = displayName(uri);
        String mime;
        try { mime = getContentResolver().getType(uri); }
        catch (RuntimeException e) { mime = null; }
        if (!selectedName.toLowerCase(java.util.Locale.ROOT).endsWith(".lua")
                && !"text/x-lua".equalsIgnoreCase(mime) && !"application/x-lua".equalsIgnoreCase(mime)) {
            setStatus(getString(R.string.lua_extensao_invalida));
            return;
        }
        long size = size(uri);
        if (size > LuaDocumentCodec.MAX_FILE_BYTES) {
            setStatus(getString(R.string.lua_tamanho_excedido));
            return;
        }
        setBusy(true, getString(R.string.lua_abrindo));
        worker.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("Não foi possível ler o documento.");
                byte[] bytes = LuaDocumentCodec.readBounded(input);
                LuaDocumentCodec.DecodedLua opened = LuaDocumentCodec.decode(bytes);
                mainThread.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    decoded = opened; sourceUri = uri; fileName = selectedName;
                    ignoreHistory = true; codeEditor.setText(opened.getText()); ignoreHistory = false;
                    history.clear(); savedText = opened.getText();
                    fileNameView.setText(fileName); codeEditor.setSelection(0); refreshEditor();
                    setBusy(false, getString(R.string.lua_arquivo_aberto, fileName,
                            Formatter.formatFileSize(this, bytes.length), opened.getCharset().name()));
                });
            } catch (Exception e) {
                mainThread.post(() -> setBusy(false, getString(R.string.lua_erro_abertura)));
            }
        });
    }

    private void validate() {
        if (decoded == null || busy) return;
        setBusy(true, getString(R.string.lua_validando));
        String snapshot = codeEditor.getText().toString();
        worker.execute(() -> {
            LuaSyntaxAnalyzer.Result result = analyzer.analyze(snapshot);
            mainThread.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                String message;
                if (result.isValid()) message = getString(R.string.lua_sem_erro_basico);
                else {
                    LuaSyntaxAnalyzer.Diagnostic issue = result.getDiagnostics().get(0);
                    message = issue.getLine() > 0
                            ? getString(R.string.lua_erro_linha, issue.getLine(), issue.getMessage())
                            : issue.getMessage();
                    if (result.getDiagnostics().size() > 1) {
                        message += " " + getString(R.string.lua_mais_erros, result.getDiagnostics().size() - 1);
                    }
                }
                setBusy(false, message);
            });
        });
    }

    private void format() {
        if (decoded == null || busy) return;
        String original = codeEditor.getText().toString();
        int selection = Math.max(0, codeEditor.getSelectionStart());
        setBusy(true, getString(R.string.lua_formatando));
        worker.execute(() -> {
            String formatted = formatter.format(original, 4);
            mainThread.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (!original.equals(formatted)) {
                    codeEditor.setText(formatted);
                    codeEditor.setSelection(Math.min(selection, formatted.length()));
                    setBusy(false, getString(R.string.lua_formatado));
                } else setBusy(false, getString(R.string.lua_sem_mudanca));
            });
        });
    }

    private void save() {
        if (decoded == null || busy) return;
        if (sourceUri == null) { saveAs(); return; }
        new AlertDialog.Builder(this).setTitle(R.string.lua_confirmar_sobrescrita)
                .setMessage(getString(R.string.lua_confirmar_sobrescrita_mensagem, fileName))
                .setNegativeButton(R.string.lua_cancelar, null)
                .setPositiveButton(R.string.lua_confirmar, (dialog, which) -> encodeAndWrite(false))
                .show();
    }

    private void saveAs() {
        if (decoded == null || busy) return;
        encodeAndWrite(true);
    }

    private void encodeAndWrite(boolean asNewDocument) {
        String snapshot = codeEditor.getText().toString();
        setBusy(true, getString(R.string.lua_preparando_salvamento));
        worker.execute(() -> {
            try {
                byte[] bytes = LuaDocumentCodec.encode(snapshot, decoded);
                mainThread.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    pendingBytes = bytes; pendingText = snapshot;
                    if (asNewDocument) launchSavePicker(); else write(sourceUri, true);
                });
            } catch (IOException e) {
                mainThread.post(() -> setBusy(false, e.getMessage()));
            }
        });
    }

    private void launchSavePicker() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/x-lua");
        intent.putExtra(Intent.EXTRA_TITLE, outputName(fileName));
        try { startActivityForResult(intent, REQUEST_SAVE_AS); }
        catch (ActivityNotFoundException e) {
            pendingBytes = null; pendingText = null;
            setBusy(false, getString(R.string.lua_seletor_indisponivel));
        }
    }

    private void write(Uri destination, boolean overwrite) {
        byte[] bytes = pendingBytes;
        String text = pendingText;
        if (bytes == null || text == null) { setBusy(false, getString(R.string.lua_erro_salvar)); return; }
        setBusy(true, getString(R.string.lua_salvando));
        worker.execute(() -> {
            try (OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) throw new IOException("Destino indisponível.");
                output.write(bytes); output.flush();
                mainThread.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (!overwrite) { sourceUri = destination; fileName = displayName(destination); fileNameView.setText(fileName); }
                    savedText = text; pendingBytes = null; pendingText = null;
                    setBusy(false, getString(R.string.lua_salvo, fileName,
                            Formatter.formatFileSize(this, bytes.length)));
                });
            } catch (Exception e) {
                if (!overwrite) {
                    try { DocumentsContract.deleteDocument(getContentResolver(), destination); }
                    catch (Exception ignored) { /* O provedor pode não permitir remover a cópia parcial. */ }
                }
                mainThread.post(() -> {
                    pendingBytes = null; pendingText = null;
                    setBusy(false, getString(R.string.lua_erro_salvar));
                });
            }
        });
    }

    private void showFindReplace() {
        if (decoded == null || busy) return;
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(32, 16, 32, 0);
        EditText find = new EditText(this); find.setHint(R.string.lua_localizar_dica);
        EditText replace = new EditText(this); replace.setHint(R.string.lua_substituir_dica);
        fields.addView(find); fields.addView(replace);
        Button replaceAll = new Button(this);
        replaceAll.setText(R.string.lua_substituir_tudo);
        fields.addView(replaceAll);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.lua_localizar_substituir)
                .setView(fields).setNegativeButton(R.string.lua_fechar, null)
                .setNeutralButton(R.string.lua_localizar_proximo, null)
                .setPositiveButton(R.string.lua_substituir_proximo, null).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> findNext(find.getText().toString()));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v ->
                    replaceNext(find.getText().toString(), replace.getText().toString()));
            replaceAll.setOnClickListener(v -> replaceAll(find.getText().toString(), replace.getText().toString()));
        });
        dialog.show();
    }

    private void findNext(String query) {
        if (TextUtils.isEmpty(query)) { setStatus(getString(R.string.lua_busca_vazia)); return; }
        String text = codeEditor.getText().toString();
        int start = Math.max(0, codeEditor.getSelectionEnd());
        int found = text.indexOf(query, start);
        if (found < 0 && start > 0) found = text.indexOf(query);
        if (found < 0) setStatus(getString(R.string.lua_nao_encontrado));
        else { codeEditor.requestFocus(); codeEditor.setSelection(found, found + query.length()); }
    }

    private void replaceNext(String query, String replacement) {
        if (TextUtils.isEmpty(query)) { setStatus(getString(R.string.lua_busca_vazia)); return; }
        int start = codeEditor.getSelectionStart(), end = codeEditor.getSelectionEnd();
        if (start >= 0 && end - start == query.length()
                && query.contentEquals(codeEditor.getText().subSequence(start, end))) {
            codeEditor.getText().replace(start, end, replacement);
        }
        findNext(query);
    }

    private void replaceAll(String query, String replacement) {
        if (TextUtils.isEmpty(query)) { setStatus(getString(R.string.lua_busca_vazia)); return; }
        String original = codeEditor.getText().toString();
        StringBuilder result = new StringBuilder(original.length());
        int position = 0;
        int count = 0;
        int found;
        while ((found = original.indexOf(query, position)) >= 0) {
            result.append(original, position, found).append(replacement);
            position = found + query.length();
            count++;
        }
        if (count == 0) { setStatus(getString(R.string.lua_nao_encontrado)); return; }
        result.append(original, position, original.length());
        if (result.length() > LuaDocumentCodec.MAX_TEXT_CHARACTERS) {
            setStatus(getString(R.string.lua_resultado_substituicao_excedido));
            return;
        }
        codeEditor.setText(result.toString());
        codeEditor.setSelection(codeEditor.length());
        setStatus(getString(R.string.lua_substituicoes_feitas, count));
    }

    private void applyHistory(UndoRedoHistory.Change change, boolean isUndo) {
        if (change == null) { setStatus(getString(isUndo ? R.string.lua_sem_desfazer : R.string.lua_sem_refazer)); return; }
        ignoreHistory = true; codeEditor.setText(change.getText());
        codeEditor.setSelection(Math.min(change.getSelection(), codeEditor.length())); ignoreHistory = false;
        refreshEditor(); updateButtons();
        setStatus(getString(isUndo ? R.string.lua_desfeito : R.string.lua_refeito));
    }

    private void refreshEditor() {
        Editable text = codeEditor.getText();
        lineNumbers.setText(makeLineNumbers(text));
        new LuaSyntaxHighlighter(getColor(R.color.lua_keyword), getColor(R.color.lua_function),
                getColor(R.color.lua_comment), getColor(R.color.lua_string), getColor(R.color.lua_number),
                getColor(R.color.lua_identifier)).apply(text);
        updateButtons();
    }

    private void setBusy(boolean value, String message) {
        busy = value; progressBar.setVisibility(value ? View.VISIBLE : View.GONE);
        statusView.setText(message); updateButtons();
    }
    private void updateButtons() {
        boolean available = decoded != null && !busy;
        openButton.setEnabled(!busy); saveButton.setEnabled(available && hasChanges());
        saveAsButton.setEnabled(available); undoButton.setEnabled(available && history.canUndo());
        redoButton.setEnabled(available && history.canRedo()); formatButton.setEnabled(available);
        validateButton.setEnabled(available); findButton.setEnabled(available); codeEditor.setEnabled(available);
    }
    private boolean hasChanges() { return decoded != null && !savedText.equals(codeEditor.getText().toString()); }
    private static String makeLineNumbers(CharSequence text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') lines++;
        StringBuilder result = new StringBuilder(lines * 4);
        for (int line = 1; line <= lines; line++) { if (line > 1) result.append('\n'); result.append(line); }
        return result.toString();
    }
    private static int countLines(CharSequence text, int start, int end) {
        int result = 0;
        for (int i = start; i < end; i++) if (text.charAt(i) == '\n') result++;
        return result;
    }
    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0 && !TextUtils.isEmpty(cursor.getString(index))) return cursor.getString(index);
            }
        } catch (RuntimeException ignored) { /* Usa o segmento da URI quando o provedor não informa nome. */ }
        String fallback = uri.getLastPathSegment();
        return TextUtils.isEmpty(fallback) ? "script.lua" : fallback;
    }
    private long size(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index);
            }
        } catch (RuntimeException ignored) { /* O limite também é aplicado durante a leitura. */ }
        return -1;
    }
    private static String outputName(String input) {
        String name = TextUtils.isEmpty(input) ? "script.lua" : input;
        return name.toLowerCase(java.util.Locale.ROOT).endsWith(".lua") ? name : name + ".lua";
    }
    private void setStatus(String message) { statusView.setText(message); }

    @Override public void onBackPressed() {
        if (!hasChanges()) { super.onBackPressed(); return; }
        new AlertDialog.Builder(this).setTitle(R.string.lua_descartar_titulo)
                .setMessage(R.string.lua_descartar_mensagem).setNegativeButton(R.string.lua_continuar, null)
                .setPositiveButton(R.string.lua_descartar, (d, w) -> finish()).show();
    }
    @Override protected void onDestroy() {
        mainThread.removeCallbacks(refreshRunnable); worker.shutdownNow(); super.onDestroy();
    }
}
