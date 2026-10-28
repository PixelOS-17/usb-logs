package net.pixelos.usblogs;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Packed logs waiting to be shared, and bug reports shared into the app. */
final class Archives {
    private static final int KEEP = 5;

    private Archives() {
    }

    static File dir(Context context) {
        return new File(context.getCacheDir(), "logs");
    }

    /** Bug reports shared to this app, added to the next archive. */
    static File pendingDir(Context context) {
        return new File(context.getFilesDir(), "bugreports");
    }

    static File create(Context context, String scenario, String extension) throws IOException {
        File dir = dir(context);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create " + dir);
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        return new File(dir, "pixelos-logs-" + scenario + "-" + stamp + extension);
    }

    /** Saved archives, newest first. */
    static File[] list(Context context) {
        return newestFirst(dir(context).listFiles(
                (dir, name) -> name.endsWith(".tar.gz") || name.endsWith(".zip")));
    }

    static File[] pending(Context context) {
        return newestFirst(pendingDir(context).listFiles());
    }

    static String type(File archive) {
        return archive.getName().endsWith(".zip") ? "application/zip" : "application/gzip";
    }

    /**
     * Bundles any pending bug reports with a freshly packed archive, then drops old archives.
     * Returns the file to share.
     */
    static File finish(Context context, File archive) throws IOException {
        File[] reports = pending(context);
        File result = archive;
        if (reports.length > 0) {
            String name = archive.getName();
            result = new File(archive.getParentFile(),
                    name.substring(0, name.indexOf('.')) + "-with-bugreport.zip");
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(result))) {
                add(zip, archive, name);
                for (File report : reports) {
                    add(zip, report, report.getName());
                }
            }
            Files.delete(archive.toPath());
            for (File report : reports) {
                Files.delete(report.toPath());
            }
        }
        File[] archives = list(context);
        for (int i = KEEP; i < archives.length; i++) {
            Files.delete(archives[i].toPath());
        }
        return result;
    }

    /** Zips a directory tree, with entries under the directory's own name. */
    static void zip(File dir, File archive) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            addTree(zip, dir, dir.getName());
        }
    }

    private static void addTree(ZipOutputStream zip, File file, String name) throws IOException {
        File[] children = file.listFiles();
        if (children == null) {
            add(zip, file, name);
            return;
        }
        for (File child : children) {
            addTree(zip, child, name + "/" + child.getName());
        }
    }

    private static void add(ZipOutputStream zip, File file, String name) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        Files.copy(file.toPath(), zip);
        zip.closeEntry();
    }

    private static File[] newestFirst(File[] files) {
        if (files == null) {
            return new File[0];
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        return files;
    }
}
