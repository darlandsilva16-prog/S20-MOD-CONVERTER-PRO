package com.fs20modconverterpro.texture;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import com.fs20modconverterpro.R;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FilterInputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Inspeção e conversão de imagens; os scripts e arquivos originais nunca são executados ou editados. */
public final class TextureConverterActivity extends Activity {
    private static final int REQUEST_OPEN = 401;
    private static final int REQUEST_EXPORT = 402;
    private static final int HEADER_LIMIT = 1024 * 1024;
    private static final int PREVIEW_MAX_SIDE = 1024;
    private static final int OUTPUT_MAX_BYTES = 32 * 1024 * 1024;
    private static final String[] IMAGE_MIME_TYPES = {
            "image/png", "image/jpeg", "image/bmp", "image/x-tga", "application/octet-stream"
    };
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler mainThread = new Handler(Looper.getMainLooper());
    private final TextureInspector inspector = new TextureInspector();
    private final TextureRasterDecoder rasterDecoder = new TextureRasterDecoder();

    private Button openButton, convertButton, zoomInButton, zoomOutButton;
    private ZoomableImageView preview;
    private TextView nameView, detailsView, statusView, qualityView;
    private TextView previewPlaceholder;
    private ProgressBar progressBar;
    private Spinner outputFormatSpinner, resolutionSpinner;
    private SeekBar qualitySlider;
    private Uri selectedUri;
    private String selectedName;
    private TextureInfo textureInfo;
    private byte[] pendingExport;
    private String pendingOutputName;
    private boolean busy;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_texture_converter);
        openButton = (Button) findViewById(R.id.texture_open);
        convertButton = (Button) findViewById(R.id.texture_convert);
        zoomInButton = (Button) findViewById(R.id.texture_zoom_in);
        zoomOutButton = (Button) findViewById(R.id.texture_zoom_out);
        preview = (ZoomableImageView) findViewById(R.id.texture_preview);
        nameView = (TextView) findViewById(R.id.texture_name);
        detailsView = (TextView) findViewById(R.id.texture_details);
        statusView = (TextView) findViewById(R.id.texture_status);
        qualityView = (TextView) findViewById(R.id.texture_quality_label);
        previewPlaceholder = (TextView) findViewById(R.id.texture_preview_placeholder);
        progressBar = (ProgressBar) findViewById(R.id.texture_progress);
        outputFormatSpinner = (Spinner) findViewById(R.id.texture_output_format);
        resolutionSpinner = (Spinner) findViewById(R.id.texture_resolution);
        qualitySlider = (SeekBar) findViewById(R.id.texture_quality);
        outputFormatSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"PNG", "JPG"}));
        resolutionSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{getString(R.string.texture_original), "4096 px", "2048 px", "1024 px", "512 px"}));
        qualitySlider.setMax(50);
        qualitySlider.setProgress(40);
        updateQualityLabel();
        qualitySlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { updateQualityLabel(); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        openButton.setOnClickListener(v -> openPicker());
        convertButton.setOnClickListener(v -> convert());
        zoomInButton.setOnClickListener(v -> preview.zoomBy(1.25f));
        zoomOutButton.setOnClickListener(v -> preview.zoomBy(0.8f));
        updateEnabled();
    }

    private void openPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, IMAGE_MIME_TYPES);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(intent, REQUEST_OPEN); }
        catch (ActivityNotFoundException e) { setStatus(getString(R.string.texture_picker_missing)); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_OPEN && requestCode != REQUEST_EXPORT) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            pendingExport = null;
            setBusy(false, getString(R.string.texture_cancelled));
            return;
        }
        if (requestCode == REQUEST_OPEN) inspect(data.getData());
        else export(data.getData());
    }

    private void inspect(Uri uri) {
        selectedUri = uri;
        selectedName = displayName(uri);
        long size = querySize(uri);
        nameView.setText(selectedName);
        textureInfo = null;
        preview.setImageBitmap(null);
        previewPlaceholder.setVisibility(View.VISIBLE);
        convertButton.setEnabled(false);
        if (size > TextureInspector.MAX_FILE_BYTES) {
            detailsView.setText(getString(R.string.texture_details_size, Formatter.formatFileSize(this, size)));
            setStatus(getString(R.string.texture_file_too_large));
            return;
        }
        setBusy(true, getString(R.string.texture_inspecting));
        String mime;
        try { mime = getContentResolver().getType(uri); }
        catch (RuntimeException e) { mime = null; }
        final String selectedMime = mime;
        worker.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("Não foi possível abrir a textura.");
                byte[] header = readPrefix(input, HEADER_LIMIT);
                TextureInfo info = inspector.inspect(header, selectedName, selectedMime, size);
                Bitmap bitmap = info.canConvert() ? decodeBitmap(uri, info, PREVIEW_MAX_SIDE) : null;
                mainThread.post(() -> {
                    if (isFinishing() || isDestroyed()) { if (bitmap != null) bitmap.recycle(); return; }
                    textureInfo = info;
                    if (bitmap != null) {
                        preview.setImageBitmap(bitmap);
                        previewPlaceholder.setVisibility(View.GONE);
                    }
                    detailsView.setText(formatDetails(info));
                    convertButton.setEnabled(info.canConvert());
                    setBusy(false, info.getMessage());
                });
            } catch (Exception e) {
                mainThread.post(() -> {
                    detailsView.setText(getString(R.string.texture_details_size,
                            size < 0 ? getString(R.string.texture_unknown_size) : Formatter.formatFileSize(this, size)));
                    setBusy(false, getString(R.string.texture_corrupt));
                });
            }
        });
    }

    private Bitmap decodeBitmap(Uri uri, TextureInfo info, int maximumSide) throws IOException {
        if (info.getFormat() == TextureInfo.Format.BMP || info.getFormat() == TextureInfo.Format.TGA
                || info.getFormat() == TextureInfo.Format.DDS) {
            byte[] bytes;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("Não foi possível abrir a textura.");
                bytes = readBounded(input, TextureInspector.MAX_FILE_BYTES);
            }
            TextureRaster raster = rasterDecoder.decode(bytes, info, maximumSide);
            return Bitmap.createBitmap(raster.getPixels(), raster.getWidth(), raster.getHeight(), Bitmap.Config.ARGB_8888);
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Não foi possível abrir a imagem.");
            BitmapFactory.decodeStream(new LimitedInputStream(input, TextureInspector.MAX_FILE_BYTES), null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                || (long) bounds.outWidth * bounds.outHeight > TextureInspector.MAX_DECODE_PIXELS) {
            throw new IOException("Resolução inválida ou acima do limite seguro.");
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = TextureRasterDecoder.sampleSize(bounds.outWidth, bounds.outHeight, maximumSide);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Não foi possível abrir a imagem.");
            decoded = BitmapFactory.decodeStream(new LimitedInputStream(input, TextureInspector.MAX_FILE_BYTES), null, options);
        }
        if (decoded == null) throw new IOException("O Android não conseguiu decodificar esta imagem.");
        return decoded;
    }

    private void convert() {
        if (busy || textureInfo == null || !textureInfo.canConvert()) return;
        int maximumSide = selectedMaximumSide();
        boolean jpeg = outputFormatSpinner.getSelectedItemPosition() == 1;
        int quality = 50 + qualitySlider.getProgress();
        boolean flattenAlpha = jpeg && textureInfo.getTransparency() == TextureInfo.Transparency.YES;
        setBusy(true, getString(flattenAlpha
                ? R.string.texture_jpeg_alpha_warning : R.string.texture_converting));
        worker.execute(() -> {
            Bitmap bitmap = null;
            try {
                bitmap = decodeBitmap(selectedUri, textureInfo, maximumSide);
                bitmap = fitMaximum(bitmap, maximumSide);
                byte[] encoded = compress(bitmap, jpeg, quality);
                String extension = jpeg ? ".jpg" : ".png";
                String output = baseName(selectedName) + "-convertido" + extension;
                Bitmap finalBitmap = bitmap;
                mainThread.post(() -> {
                    if (isFinishing() || isDestroyed()) { finalBitmap.recycle(); return; }
                    pendingExport = encoded;
                    pendingOutputName = output;
                    launchExportPicker();
                    finalBitmap.recycle();
                });
            } catch (Exception e) {
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                mainThread.post(() -> setBusy(false,
                        e.getMessage() == null ? getString(R.string.texture_conversion_failed) : e.getMessage()));
            }
        });
    }

    private void launchExportPicker() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(outputFormatSpinner.getSelectedItemPosition() == 1 ? "image/jpeg" : "image/png");
        intent.putExtra(Intent.EXTRA_TITLE, pendingOutputName);
        try { startActivityForResult(intent, REQUEST_EXPORT); }
        catch (ActivityNotFoundException e) {
            pendingExport = null;
            setBusy(false, getString(R.string.texture_picker_missing));
        }
    }

    private void export(Uri destination) {
        byte[] bytes = pendingExport;
        pendingExport = null;
        if (bytes == null) { setBusy(false, getString(R.string.texture_conversion_failed)); return; }
        setBusy(true, getString(R.string.texture_saving));
        worker.execute(() -> {
            try (OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) throw new IOException("O destino não está disponível.");
                TextureExportWriter.write(output, bytes);
                mainThread.post(() -> setBusy(false,
                        getString(R.string.texture_saved, pendingOutputName, Formatter.formatFileSize(this, bytes.length))));
            } catch (Exception e) {
                try { DocumentsContract.deleteDocument(getContentResolver(), destination); }
                catch (Exception ignored) { /* Alguns provedores não permitem remover uma saída parcial. */ }
                mainThread.post(() -> setBusy(false, getString(R.string.texture_save_failed)));
            }
        });
    }

    private Bitmap fitMaximum(Bitmap source, int maximumSide) {
        int width = source.getWidth(), height = source.getHeight();
        if (Math.max(width, height) <= maximumSide) return source;
        float scale = (float) maximumSide / Math.max(width, height);
        int outWidth = Math.max(1, Math.round(width * scale));
        int outHeight = Math.max(1, Math.round(height * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(source, outWidth, outHeight, true);
        if (scaled != source) source.recycle();
        return scaled;
    }

    private byte[] compress(Bitmap image, boolean jpeg, int quality) throws IOException {
        Bitmap outputImage = image;
        if (jpeg && image.hasAlpha()) {
            outputImage = Bitmap.createBitmap(image.getWidth(), image.getHeight(), Bitmap.Config.RGB_565);
            Canvas canvas = new Canvas(outputImage);
            canvas.drawColor(Color.WHITE);
            canvas.drawBitmap(image, 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
        }
        BoundedOutputStream stream = new BoundedOutputStream(OUTPUT_MAX_BYTES);
        boolean success;
        try {
            success = outputImage.compress(jpeg ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG,
                    jpeg ? quality : 100, stream);
        } finally {
            if (outputImage != image) outputImage.recycle();
        }
        if (!success || stream.size() == 0) throw new IOException("Não foi possível codificar a imagem de saída.");
        return stream.toByteArray();
    }

    private String formatDetails(TextureInfo info) {
        String size = info.getSize() < 0 ? getString(R.string.texture_unknown_size)
                : Formatter.formatFileSize(this, info.getSize());
        String dimensions = info.hasDimensions() ? info.getWidth() + " × " + info.getHeight()
                : getString(R.string.texture_dimensions_unknown);
        String alpha = info.getTransparency() == TextureInfo.Transparency.YES ? getString(R.string.texture_alpha_yes)
                : info.getTransparency() == TextureInfo.Transparency.NO ? getString(R.string.texture_alpha_no)
                : getString(R.string.texture_alpha_unknown);
        return getString(R.string.texture_details, info.getFormat().name(), dimensions, size,
                alpha, info.getVariant().isEmpty() ? getString(R.string.texture_variant_unknown) : info.getVariant());
    }

    private int selectedMaximumSide() {
        int selected = resolutionSpinner.getSelectedItemPosition();
        if (selected == 0) return 4096;
        return new int[]{4096, 4096, 2048, 1024, 512}[selected];
    }
    private void updateQualityLabel() {
        if (qualityView != null && qualitySlider != null)
            qualityView.setText(getString(R.string.texture_quality, 50 + qualitySlider.getProgress()));
    }
    private void setBusy(boolean value, String message) {
        busy = value; progressBar.setVisibility(value ? View.VISIBLE : View.GONE);
        statusView.setText(message); updateEnabled();
    }
    private void updateEnabled() {
        if (openButton == null) return;
        openButton.setEnabled(!busy);
        convertButton.setEnabled(!busy && textureInfo != null && textureInfo.canConvert());
        outputFormatSpinner.setEnabled(!busy); resolutionSpinner.setEnabled(!busy); qualitySlider.setEnabled(!busy);
        zoomInButton.setEnabled(preview.getDrawable() != null);
        zoomOutButton.setEnabled(preview.getDrawable() != null);
    }
    private void setStatus(String message) { statusView.setText(message); }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0 && !TextUtils.isEmpty(cursor.getString(index))) return cursor.getString(index);
            }
        } catch (RuntimeException ignored) { /* Usa o nome disponível na URI. */ }
        String fallback = uri.getLastPathSegment();
        return TextUtils.isEmpty(fallback) ? "textura" : fallback;
    }
    private long querySize(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index);
            }
        } catch (RuntimeException ignored) { /* A leitura continua limitada pelo fluxo. */ }
        return -1;
    }
    private static byte[] readPrefix(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int count;
        while (output.size() < limit && (count = input.read(buffer, 0, Math.min(buffer.length, limit - output.size()))) != -1) {
            if (count > 0) output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
    private static byte[] readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (count == 0) continue;
            if (output.size() > limit - count) throw new IOException("A textura excede o limite de 32 MiB.");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
    private static String baseName(String filename) {
        if (filename == null) return "textura";
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private final long maximum;
        private long consumed;
        LimitedInputStream(InputStream input, long maximum) { super(input); this.maximum = maximum; }
        @Override public int read() throws IOException {
            if (consumed >= maximum) return -1;
            int value = super.read();
            if (value >= 0) consumed++;
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (consumed >= maximum) return -1;
            int allowed = (int) Math.min(length, maximum - consumed);
            int count = super.read(bytes, offset, allowed);
            if (count > 0) consumed += count;
            return count;
        }
        @Override public long skip(long amount) throws IOException {
            long skipped = super.skip(Math.min(amount, maximum - consumed));
            consumed += skipped;
            return skipped;
        }
    }

    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }

    private static final class BoundedOutputStream extends ByteArrayOutputStream {
        private final int limit;
        BoundedOutputStream(int limit) { this.limit = limit; }
        @Override public synchronized void write(int value) {
            if (count >= limit) throw new IllegalStateException("Imagem de saída acima do limite.");
            super.write(value);
        }
        @Override public synchronized void write(byte[] data, int offset, int length) {
            if (length > limit - count) throw new IllegalStateException("Imagem de saída acima do limite.");
            super.write(data, offset, length);
        }
    }
}
