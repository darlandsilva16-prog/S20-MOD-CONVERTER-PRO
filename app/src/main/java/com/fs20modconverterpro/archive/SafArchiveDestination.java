package com.fs20modconverterpro.archive;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Grava arquivos por SAF dentro da árvore escolhida pela pessoa usuária. */
public final class SafArchiveDestination implements ArchiveDestination {
    private static final String[] CHILD_COLUMNS = {
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE
    };

    private final ContentResolver resolver;
    private final Uri treeUri;
    private final Uri rootUri;
    private final Map<String, Uri> directories = new HashMap<>();
    private final List<Uri> createdDocuments = new ArrayList<>();

    public SafArchiveDestination(ContentResolver resolver, Uri treeUri) throws IOException {
        if (resolver == null || treeUri == null) {
            throw new IOException("A pasta de destino escolhida não está disponível.");
        }
        this.resolver = resolver;
        this.treeUri = treeUri;
        try {
            String rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
            this.rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId);
            directories.put("", rootUri);
        } catch (RuntimeException exception) {
            throw new IOException("Não foi possível acessar a pasta de destino.", exception);
        }
    }

    @Override
    public void createDirectory(String relativePath) throws IOException {
        ensureDirectory(relativePath);
    }

    @Override
    public OutputStream createFile(String relativePath) throws IOException {
        String[] segments = splitPath(relativePath);
        if (segments.length == 0) {
            throw new IOException("O caminho do arquivo de destino está vazio.");
        }
        StringBuilder parentPath = new StringBuilder();
        for (int index = 0; index < segments.length - 1; index++) {
            if (index > 0) {
                parentPath.append('/');
            }
            parentPath.append(segments[index]);
        }
        Uri parent = parentPath.length() == 0
                ? rootUri
                : ensureDirectory(parentPath.toString());
        String fileName = segments[segments.length - 1];
        if (findChild(parent, fileName) != null) {
            throw new IOException("Já existe um item com o nome " + fileName + " no destino.");
        }

        Uri created;
        try {
            created = DocumentsContract.createDocument(
                    resolver, parent, "application/octet-stream", fileName);
        } catch (RuntimeException exception) {
            throw new IOException("Não foi possível criar o arquivo " + fileName + ".", exception);
        }
        if (created == null) {
            throw new IOException("Não foi possível criar o arquivo " + fileName + ".");
        }
        createdDocuments.add(created);

        String actualName = getDisplayName(created);
        if (actualName != null && !fileName.equals(actualName)) {
            throw new IOException("O provedor de arquivos alterou o nome " + fileName + ".");
        }

        try {
            OutputStream output = resolver.openOutputStream(created, "w");
            if (output == null) {
                throw new IOException("O provedor não abriu o arquivo para gravação.");
            }
            return output;
        } catch (RuntimeException exception) {
            throw new IOException("Não foi possível gravar o arquivo " + fileName + ".", exception);
        }
    }

    @Override
    public void rollback() {
        for (int index = createdDocuments.size() - 1; index >= 0; index--) {
            try {
                DocumentsContract.deleteDocument(resolver, createdDocuments.get(index));
            } catch (Exception ignored) {
                // A limpeza é de melhor esforço; nunca apaga itens que já existiam.
            }
        }
        createdDocuments.clear();
        directories.clear();
        directories.put("", rootUri);
    }

    private Uri ensureDirectory(String relativePath) throws IOException {
        String[] segments = splitPath(relativePath);
        if (segments.length == 0) {
            return rootUri;
        }

        Uri parent = rootUri;
        StringBuilder currentPath = new StringBuilder();
        for (String segment : segments) {
            if (currentPath.length() > 0) {
                currentPath.append('/');
            }
            currentPath.append(segment);
            String pathKey = currentPath.toString();
            Uri directory = directories.get(pathKey);
            if (directory == null) {
                ChildDocument existing = findChild(parent, segment);
                if (existing != null) {
                    if (!Document.MIME_TYPE_DIR.equals(existing.mimeType)) {
                        throw new IOException("O caminho de pasta já existe como arquivo: " + segment + ".");
                    }
                    directory = existing.uri;
                } else {
                    try {
                        directory = DocumentsContract.createDocument(
                                resolver, parent, Document.MIME_TYPE_DIR, segment);
                    } catch (RuntimeException exception) {
                        throw new IOException("Não foi possível criar a pasta " + segment + ".", exception);
                    }
                    if (directory == null) {
                        throw new IOException("Não foi possível criar a pasta " + segment + ".");
                    }
                    createdDocuments.add(directory);
                }
                directories.put(pathKey, directory);
            }
            parent = directory;
        }
        return parent;
    }

    private ChildDocument findChild(Uri parentUri, String displayName) throws IOException {
        Uri childrenUri;
        try {
            String parentId = DocumentsContract.getDocumentId(parentUri);
            childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
        } catch (RuntimeException exception) {
            throw new IOException("Não foi possível consultar a pasta de destino.", exception);
        }

        try (Cursor cursor = resolver.query(childrenUri, CHILD_COLUMNS, null, null, null)) {
            if (cursor == null) {
                throw new IOException("O provedor não permitiu consultar a pasta de destino.");
            }
            int idColumn = cursor.getColumnIndex(Document.COLUMN_DOCUMENT_ID);
            int nameColumn = cursor.getColumnIndex(Document.COLUMN_DISPLAY_NAME);
            int mimeColumn = cursor.getColumnIndex(Document.COLUMN_MIME_TYPE);
            if (idColumn < 0 || nameColumn < 0 || mimeColumn < 0) {
                throw new IOException("O provedor retornou informações incompletas da pasta.");
            }
            while (cursor.moveToNext()) {
                if (displayName.equals(cursor.getString(nameColumn))) {
                    String documentId = cursor.getString(idColumn);
                    Uri childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
                    return new ChildDocument(childUri, cursor.getString(mimeColumn));
                }
            }
            return null;
        } catch (RuntimeException exception) {
            throw new IOException("Falha ao consultar o conteúdo da pasta de destino.", exception);
        }
    }

    private String getDisplayName(Uri documentUri) {
        try (Cursor cursor = resolver.query(
                documentUri,
                new String[]{Document.COLUMN_DISPLAY_NAME},
                null,
                null,
                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(Document.COLUMN_DISPLAY_NAME);
                if (column >= 0) {
                    return cursor.getString(column);
                }
            }
        } catch (RuntimeException ignored) {
            // Alguns provedores não expõem o nome ao consultar o documento recém-criado.
        }
        return null;
    }

    private static String[] splitPath(String relativePath) throws IOException {
        if (relativePath == null || relativePath.isEmpty()
                || relativePath.startsWith("/") || relativePath.indexOf('\\') >= 0) {
            throw new IOException("O caminho relativo de destino é inválido.");
        }
        String[] segments = relativePath.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IOException("O caminho relativo contém um segmento inválido.");
            }
        }
        return segments;
    }

    private static final class ChildDocument {
        final Uri uri;
        final String mimeType;

        ChildDocument(Uri uri, String mimeType) {
            this.uri = uri;
            this.mimeType = mimeType;
        }
    }
}
