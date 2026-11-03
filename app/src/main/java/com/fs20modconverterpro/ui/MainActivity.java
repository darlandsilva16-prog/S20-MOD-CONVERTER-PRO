package com.fs20modconverterpro.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.text.format.Formatter;

import com.fs20modconverterpro.R;
import com.fs20modconverterpro.archive.ArchiveEntryInfo;
import com.fs20modconverterpro.archive.ArchiveExtractionResult;
import com.fs20modconverterpro.archive.ArchiveHandler;
import com.fs20modconverterpro.archive.ArchiveInspection;
import com.fs20modconverterpro.archive.ArchiveProgressListener;
import com.fs20modconverterpro.archive.ModArchiveManager;
import com.fs20modconverterpro.archive.SafArchiveDestination;
import com.fs20modconverterpro.editor.XmlEditorActivity;
import com.fs20modconverterpro.editor.LuaEditorActivity;
import com.fs20modconverterpro.texture.TextureConverterActivity;
import com.fs20modconverterpro.viewer3d.ModelViewerActivity;
import com.fs20modconverterpro.python.PythonEngineActivity;
import com.fs20modconverterpro.conversion.ModConverterActivity;
import com.fs20modconverterpro.gar.GarManagerActivity;
import com.fs20modconverterpro.gar.GarEngineActivity;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tela do gerenciador de arquivos e extrator de mods. */
public final class MainActivity extends Activity {
    private static final int REQUEST_OPEN_ARCHIVE = 101;
    private static final int REQUEST_PICK_DESTINATION = 102;
    private static final String[] ZIP_MIME_TYPES = {
            "application/zip",
            "application/x-zip-compressed"
    };

    private final ExecutorService archiveExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainThread = new Handler(Looper.getMainLooper());
    private final ModArchiveManager archiveManager = new ModArchiveManager();

    private Button openButton;
    private Button editorButton;
    private Button luaEditorButton;
    private Button textureConverterButton;
    private Button viewer3dButton;
    private Button pythonEngineButton;
    private Button modConverterButton;
    private Button garManagerButton;
    private Button garEngineButton;
    private Button extractButton;
    private ProgressBar progressBar;
    private TextView selectedNameView;
    private TextView selectedSizeView;
    private TextView contentView;
    private TextView statusView;

    private Uri selectedArchiveUri;
    private String selectedArchiveName;
    private ArchiveHandler selectedArchiveHandler;
    private ArchiveInspection selectedInspection;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        openButton = (Button) findViewById(R.id.abrir_mod_button);
        editorButton = (Button) findViewById(R.id.abrir_editor_xml_button);
        luaEditorButton = (Button) findViewById(R.id.abrir_editor_lua_button);
        textureConverterButton = (Button) findViewById(R.id.abrir_conversor_texturas_button);
        viewer3dButton = (Button) findViewById(R.id.abrir_visualizador_3d_button);
        pythonEngineButton = (Button) findViewById(R.id.abrir_motor_python_button);
        modConverterButton = (Button) findViewById(R.id.abrir_conversor_mods_button);
        garManagerButton = (Button) findViewById(R.id.abrir_gerenciador_gar_button);
        garEngineButton = (Button) findViewById(R.id.abrir_gar_engine_button);
        extractButton = (Button) findViewById(R.id.extrair_button);
        progressBar = (ProgressBar) findViewById(R.id.progresso);
        selectedNameView = (TextView) findViewById(R.id.nome_arquivo);
        selectedSizeView = (TextView) findViewById(R.id.tamanho_arquivo);
        contentView = (TextView) findViewById(R.id.conteudo_arquivo);
        statusView = (TextView) findViewById(R.id.status);

        openButton.setOnClickListener(view -> openArchivePicker());
        editorButton.setOnClickListener(view ->
                startActivity(new Intent(this, XmlEditorActivity.class)));
        luaEditorButton.setOnClickListener(view ->
                startActivity(new Intent(this, LuaEditorActivity.class)));
        textureConverterButton.setOnClickListener(view ->
                startActivity(new Intent(this, TextureConverterActivity.class)));
        viewer3dButton.setOnClickListener(view ->
                startActivity(new Intent(this, ModelViewerActivity.class)));
        pythonEngineButton.setOnClickListener(view ->
                startActivity(new Intent(this, PythonEngineActivity.class)));
        modConverterButton.setOnClickListener(view ->
                startActivity(new Intent(this, ModConverterActivity.class)));
        garManagerButton.setOnClickListener(view ->
                startActivity(new Intent(this, GarManagerActivity.class)));
        garEngineButton.setOnClickListener(view ->
                startActivity(new Intent(this, GarEngineActivity.class)));
        extractButton.setOnClickListener(view -> openDestinationPicker());
        extractButton.setEnabled(false);
    }

    private void openArchivePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, ZIP_MIME_TYPES);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_OPEN_ARCHIVE);
        } catch (ActivityNotFoundException exception) {
            statusView.setText(R.string.erro_seletor_arquivo);
        }
    }

    private void openDestinationPicker() {
        if (selectedArchiveUri == null || selectedArchiveHandler == null
                || selectedInspection == null || !selectedInspection.canExtract()) {
            statusView.setText(R.string.erro_arquivo_nao_pronto);
            return;
        }

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_PICK_DESTINATION);
        } catch (ActivityNotFoundException exception) {
            statusView.setText(R.string.erro_seletor_pasta);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            if (requestCode == REQUEST_OPEN_ARCHIVE || requestCode == REQUEST_PICK_DESTINATION) {
                statusView.setText(R.string.operacao_cancelada);
            }
            return;
        }

        if (requestCode == REQUEST_OPEN_ARCHIVE) {
            handleArchiveSelection(data.getData());
        } else if (requestCode == REQUEST_PICK_DESTINATION) {
            handleDestinationSelection(data);
        }
    }

    private void handleArchiveSelection(Uri archiveUri) {
        selectedArchiveUri = archiveUri;
        selectedArchiveName = queryDisplayName(archiveUri);
        long archiveSize = querySize(archiveUri);
        selectedArchiveHandler = archiveManager.findHandler(
                selectedArchiveName, getContentResolver().getType(archiveUri));
        selectedInspection = null;
        extractButton.setEnabled(false);

        selectedNameView.setText(getString(R.string.arquivo_nome, selectedArchiveName));
        selectedSizeView.setText(getString(
                R.string.arquivo_tamanho,
                archiveSize < 0
                        ? getString(R.string.tamanho_desconhecido)
                        : Formatter.formatFileSize(this, archiveSize)));

        if (selectedArchiveHandler == null) {
            contentView.setText(R.string.formato_nao_suportado);
            statusView.setText(R.string.selecione_um_zip);
            return;
        }

        setBusy(true, getString(R.string.analisando_arquivo));
        ArchiveHandler handler = selectedArchiveHandler;
        archiveExecutor.execute(() -> inspectArchive(archiveUri, handler));
    }

    private void inspectArchive(Uri archiveUri, ArchiveHandler handler) {
        try (InputStream input = getContentResolver().openInputStream(archiveUri)) {
            if (input == null) {
                throw new IOException("Não foi possível abrir o arquivo selecionado.");
            }
            ArchiveInspection inspection = handler.inspect(input, progressListener(false));
            mainThread.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                selectedInspection = inspection;
                renderInspection(inspection);
                String status;
                if (inspection.canExtract()) {
                    status = getString(
                            R.string.analise_concluida,
                            inspection.getTotalEntries(),
                            Formatter.formatFileSize(this, inspection.getTotalUncompressedBytes()));
                } else if (inspection.getUnsafeEntries() > 0) {
                    status = getString(R.string.caminhos_inseguros, inspection.getUnsafeEntries());
                } else {
                    status = getString(R.string.extracao_bloqueada);
                }
                setBusy(false, status);
            });
        } catch (Exception exception) {
            mainThread.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                selectedInspection = null;
                contentView.setText(R.string.erro_leitura_zip);
                setBusy(false, R.string.erro_leitura_zip);
            });
        }
    }

    private void renderInspection(ArchiveInspection inspection) {
        StringBuilder listing = new StringBuilder();
        for (ArchiveEntryInfo entry : inspection.getEntries()) {
            if (listing.length() > 0) {
                listing.append('\n');
            }
            if (!entry.isSafe()) {
                listing.append(getString(R.string.item_bloqueado));
            } else if (entry.isDirectory()) {
                listing.append(getString(R.string.rotulo_pasta));
            } else {
                listing.append(getString(R.string.rotulo_arquivo));
            }
            listing.append(' ').append(entry.getName());
            if (!entry.isDirectory()) {
                listing.append("  —  ")
                        .append(Formatter.formatFileSize(this, entry.getUncompressedSize()));
            }
        }
        if (inspection.isTruncated()) {
            listing.append('\n').append(getString(
                    R.string.lista_truncada,
                    inspection.getTotalEntries() - inspection.getEntries().size()));
        }
        contentView.setText(listing);
        extractButton.setEnabled(inspection.canExtract());
        if (inspection.getUnsafeEntries() > 0) {
            statusView.setText(getString(
                    R.string.caminhos_inseguros,
                    inspection.getUnsafeEntries()));
        }
    }

    private void handleDestinationSelection(Intent data) {
        Uri destinationUri = data.getData();
        int grantedFlags = data.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (grantedFlags != 0) {
            try {
                getContentResolver().takePersistableUriPermission(destinationUri, grantedFlags);
            } catch (RuntimeException ignored) {
                // A permissão temporária ainda permite concluir esta extração.
            }
        }

        setBusy(true, getString(R.string.iniciando_extracao));
        Uri archiveUri = selectedArchiveUri;
        ArchiveHandler handler = selectedArchiveHandler;
        archiveExecutor.execute(() -> extractArchive(archiveUri, handler, destinationUri));
    }

    private void extractArchive(Uri archiveUri, ArchiveHandler handler, Uri destinationUri) {
        try (InputStream input = getContentResolver().openInputStream(archiveUri)) {
            if (input == null) {
                throw new IOException("Não foi possível abrir o arquivo selecionado.");
            }
            SafArchiveDestination destination = new SafArchiveDestination(
                    getContentResolver(), destinationUri);
            ArchiveExtractionResult result = handler.extract(
                    input, destination, progressListener(true));
            mainThread.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                setBusy(false, getString(
                        R.string.extracao_concluida,
                        result.getExtractedEntries(),
                        Formatter.formatFileSize(this, result.getExtractedBytes())));
            });
        } catch (Exception exception) {
            mainThread.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                setBusy(false, R.string.erro_extracao);
            });
        }
    }

    private ArchiveProgressListener progressListener(boolean extracting) {
        return (processedEntries, processedBytes, currentEntry) -> {
            String safeName = currentEntry == null ? "" : currentEntry;
            if (safeName.length() > 72) {
                safeName = safeName.substring(0, 69) + "...";
            }
            String entryName = safeName;
            mainThread.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                int message = extracting
                        ? R.string.progresso_extracao
                        : R.string.progresso_analise;
                statusView.setText(getString(
                        message,
                        processedEntries,
                        Formatter.formatFileSize(this, processedBytes),
                        entryName));
            });
        };
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME},
                null,
                null,
                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0 && !TextUtils.isEmpty(cursor.getString(column))) {
                    return cursor.getString(column);
                }
            }
        } catch (RuntimeException ignored) {
            // Usa o identificador da URI quando o provedor não informa o nome.
        }
        String fallback = uri.getLastPathSegment();
        return TextUtils.isEmpty(fallback) ? getString(R.string.nome_arquivo_desconhecido) : fallback;
    }

    private long querySize(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri,
                new String[]{OpenableColumns.SIZE},
                null,
                null,
                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (column >= 0 && !cursor.isNull(column)) {
                    return cursor.getLong(column);
                }
            }
        } catch (RuntimeException ignored) {
            // Alguns provedores não informam o tamanho do documento.
        }
        return -1;
    }

    private void setBusy(boolean isBusy, String message) {
        progressBar.setVisibility(isBusy ? View.VISIBLE : View.GONE);
        progressBar.setIndeterminate(true);
        openButton.setEnabled(!isBusy);
        extractButton.setEnabled(!isBusy
                && selectedInspection != null
                && selectedInspection.canExtract());
        statusView.setText(message);
    }

    private void setBusy(boolean isBusy, int messageResource) {
        setBusy(isBusy, getString(messageResource));
    }

    @Override
    protected void onDestroy() {
        archiveExecutor.shutdownNow();
        super.onDestroy();
    }
}
