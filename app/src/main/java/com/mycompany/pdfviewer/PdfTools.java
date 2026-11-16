package com.mycompany.pdfviewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;

import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.PDResources;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission;
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import com.tom_roush.pdfbox.pdmodel.graphics.PDXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** PDF tools: merge, extract pages, compress, images to PDF, PDF to images, lock, unlock. Call from a background thread. */
public final class PdfTools {
    private PdfTools() {}

    private static InputStream open(Context c, Uri u) throws IOException {
        InputStream is = c.getContentResolver().openInputStream(u);
        if (is == null) throw new IOException("Cannot read file");
        return is;
    }

    // ---------- page range like "1-3,5" or "all" ----------
    public static List<Integer> parseRanges(String text, int total) {
        List<Integer> pages = new ArrayList<>();
        String t = text.trim().toLowerCase();
        if (t.isEmpty() || t.equals("all")) {
            for (int i = 0; i < total; i++) pages.add(i);
            return pages;
        }
        for (String part : t.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            try {
                int a, b;
                if (part.contains("-")) {
                    String[] ab = part.split("-");
                    a = Integer.parseInt(ab[0].trim());
                    b = Integer.parseInt(ab[1].trim());
                } else {
                    a = b = Integer.parseInt(part);
                }
                if (a < 1 || b < a || b > total) throw new IllegalArgumentException("Pages must be between 1 and " + total);
                for (int i = a; i <= b; i++) pages.add(i - 1);
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                throw new IllegalArgumentException("Write pages like 1-3,5");
            }
        }
        if (pages.isEmpty()) throw new IllegalArgumentException("No pages selected");
        return pages;
    }

    // ---------- merge ----------
    public static void merge(Context c, List<Uri> uris, File out) throws IOException {
        PDDocument dest = new PDDocument();
        List<PDDocument> open = new ArrayList<>();
        try {
            for (Uri u : uris) {
                try (InputStream is = open(c, u)) {
                    PDDocument d = PDDocument.load(is);
                    open.add(d);
                    for (PDPage p : d.getPages()) dest.importPage(p);
                }
            }
            dest.save(out);
        } finally {
            for (PDDocument d : open) { try { d.close(); } catch (Exception ignored) {} }
            dest.close();
        }
    }

    // ---------- split / extract ----------
    public static void extractPages(Context c, Uri uri, List<Integer> pages, File out) throws IOException {
        try (InputStream is = open(c, uri)) {
            PDDocument src = PDDocument.load(is);
            PDDocument dest = new PDDocument();
            try {
                for (int idx : pages) dest.importPage(src.getPage(idx));
                dest.save(out);
            } finally {
                dest.close();
                src.close();
            }
        }
    }

    // ---------- compress (re-encodes big images as smaller JPEGs) ----------
    public static void compress(Context c, Uri uri, File out, int quality, int maxDim) throws IOException {
        try (InputStream is = open(c, uri)) {
            PDDocument doc = PDDocument.load(is);
            try {
                for (PDPage page : doc.getPages()) {
                    PDResources res = page.getResources();
                    if (res == null) continue;
                    List<COSName> names = new ArrayList<>();
                    for (COSName n : res.getXObjectNames()) names.add(n);
                    for (COSName name : names) {
                        try {
                            PDXObject x = res.getXObject(name);
                            if (!(x instanceof PDImageXObject)) continue;
                            PDImageXObject img = (PDImageXObject) x;
                            if (img.getWidth() < 700 || img.isStencil() || img.getSoftMask() != null) continue;
                            Bitmap bmp = img.getImage();
                            if (bmp == null) continue;
                            int w = bmp.getWidth(), h = bmp.getHeight();
                            float s = Math.min(1f, maxDim / (float) Math.max(w, h));
                            if (s < 1f) {
                                Bitmap scaled = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)), true);
                                bmp.recycle();
                                bmp = scaled;
                            }
                            PDImageXObject ni = JPEGFactory.createFromImage(doc, bmp, quality / 100f);
                            res.put(name, ni);
                            bmp.recycle();
                        } catch (Throwable ignored) {
                            // skip this image, keep original
                        }
                    }
                }
                doc.save(out);
            } finally {
                doc.close();
            }
        }
    }

    // ---------- images -> PDF ----------
    public static void imagesToPdf(Context c, List<Uri> images, File out) throws IOException {
        PDDocument doc = new PDDocument();
        try {
            for (Uri u : images) {
                Bitmap b = decodeSampled(c, u, 2000);
                if (b == null) continue;
                float pageW = 595f;
                float pageH = pageW * b.getHeight() / b.getWidth();
                PDPage page = new PDPage(new PDRectangle(pageW, pageH));
                doc.addPage(page);
                PDImageXObject img = JPEGFactory.createFromImage(doc, b, 0.85f);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.drawImage(img, 0, 0, pageW, pageH);
                }
                b.recycle();
            }
            if (doc.getNumberOfPages() == 0) throw new IOException("No image could be read");
            doc.save(out);
        } finally {
            doc.close();
        }
    }

    private static Bitmap decodeSampled(Context c, Uri u, int maxDim) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream is = open(c, u)) { BitmapFactory.decodeStream(is, null, o); }
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / sample > maxDim) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap b;
        try (InputStream is = open(c, u)) { b = BitmapFactory.decodeStream(is, null, o2); }
        if (b == null) return null;

        // fix phone-camera rotation
        if (Build.VERSION.SDK_INT >= 24) {
            try (InputStream is = open(c, u)) {
                android.media.ExifInterface exif = new android.media.ExifInterface(is);
                int ori = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
                int deg = ori == 6 ? 90 : ori == 3 ? 180 : ori == 8 ? 270 : 0;
                if (deg != 0) {
                    android.graphics.Matrix m = new android.graphics.Matrix();
                    m.postRotate(deg);
                    Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                    b.recycle();
                    b = r;
                }
            } catch (Exception ignored) {}
        }
        return b;
    }

    // ---------- PDF -> images ----------
    public static int pdfToImages(Context c, Uri uri, List<Integer> pages, String baseName) throws IOException {
        int saved = 0;
        for (int idx : pages) {
            Bitmap b = PdfUtils.renderPage(c, uri, idx, 1600);
            try {
                PdfUtils.saveBitmapToPictures(c, b, baseName + "_page" + (idx + 1) + "_" + System.currentTimeMillis());
                saved++;
            } finally {
                b.recycle();
            }
        }
        return saved;
    }

    // ---------- password ----------
    public static void protect(Context c, Uri uri, String password, File out) throws IOException {
        try (InputStream is = open(c, uri)) {
            PDDocument doc = PDDocument.load(is);
            try {
                AccessPermission ap = new AccessPermission();
                StandardProtectionPolicy policy =
                        new StandardProtectionPolicy(UUID.randomUUID().toString(), password, ap);
                policy.setEncryptionKeyLength(128);
                doc.protect(policy);
                doc.save(out);
            } finally {
                doc.close();
            }
        }
    }

    public static void unprotect(Context c, Uri uri, String password, File out) throws IOException {
        try (InputStream is = open(c, uri)) {
            PDDocument doc = PDDocument.load(is, password);
            try {
                doc.setAllSecurityToBeRemoved(true);
                doc.save(out);
            } finally {
                doc.close();
            }
        }
    }
}
