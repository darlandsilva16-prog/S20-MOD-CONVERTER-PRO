package com.fs20modconverterpro.viewer3d;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Lista modelos em ZIP e lê a entrada escolhida em memória, sem extração no sistema de arquivos. */
public final class ZipModelCatalog {
    public static final int MAX_ARCHIVE_BYTES = 32 * 1024 * 1024, MAX_EXPANDED_BYTES = 256 * 1024 * 1024, MAX_ENTRIES = 20000;
    public List<String> list(byte[] archive) throws IOException {
        checkArchive(archive);
        ArrayList<String> models = new ArrayList<>(); long total=0; int entries=0;
        try (ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry; byte[] buffer=new byte[8192];
            while ((entry=zip.getNextEntry())!=null) {
                if (++entries>MAX_ENTRIES) throw new IOException("O ZIP excede 20 mil itens.");
                long size=0; int n;
                while ((n=zip.read(buffer))!=-1) { size+=n; total+=n; if (total>MAX_EXPANDED_BYTES) throw new IOException("O conteúdo descompactado excede 256 MiB."); }
                if (!entry.isDirectory() && ModelFormat.fromName(entry.getName()) != ModelFormat.UNKNOWN && ModelFormat.fromName(entry.getName()) != ModelFormat.ZIP) models.add(entry.getName());
                zip.closeEntry();
            }
        }
        return Collections.unmodifiableList(models);
    }
    public List<String> listPaths(byte[] archive) throws IOException {
        checkArchive(archive); ArrayList<String> paths=new ArrayList<>(); long total=0; int entries=0;
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(archive))){ZipEntry entry;byte[] buffer=new byte[8192];while((entry=zip.getNextEntry())!=null){if(++entries>MAX_ENTRIES)throw new IOException("O ZIP excede 20 mil itens.");int n;while((n=zip.read(buffer))!=-1){total+=n;if(total>MAX_EXPANDED_BYTES)throw new IOException("O conteúdo descompactado excede 256 MiB.");}if(!entry.isDirectory())paths.add(entry.getName().replace('\\','/'));zip.closeEntry();}}
        return Collections.unmodifiableList(paths);
    }
    public byte[] read(byte[] archive, String selected) throws IOException {
        checkArchive(archive); byte[] buffer=new byte[8192];
        try (ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry; int entries=0;
            while ((entry=zip.getNextEntry())!=null) {
                if (++entries>MAX_ENTRIES) throw new IOException("O ZIP excede 20 mil itens.");
                if (entry.getName().equals(selected)) {
                    ByteArrayOutputStream out=new ByteArrayOutputStream(); int n;
                    while ((n=zip.read(buffer))!=-1) { if ((long)out.size()+n>ModelLoader.MAX_MODEL_BYTES) throw new IOException("O modelo selecionado excede 32 MiB."); out.write(buffer,0,n); }
                    return out.toByteArray();
                }
                while (zip.read(buffer)!=-1) { }
            }
        }
        throw new IOException("O modelo escolhido não foi encontrado no ZIP.");
    }
    private static void checkArchive(byte[] bytes) throws IOException { if(bytes==null||bytes.length<4||bytes.length>MAX_ARCHIVE_BYTES) throw new IOException("ZIP vazio ou acima do limite de 32 MiB."); }
}
