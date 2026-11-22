package net.pixelos.usblogs;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Captures without root or Shizuku: snapshots of what any app can read, and a timeline that
 * Recorder writes while the capture runs. Sessions mirror capture.sh's layout.
 */
final class AppCapture implements Capture {
    private static final int KEEP_SESSIONS = 5;

    private final Context context;

    AppCapture(Context context) {
        this.context = context.getApplicationContext();
    }

    static boolean hasReadLogs(Context context) {
        return context.checkSelfPermission(Manifest.permission.READ_LOGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    static File sessions(Context context) {
        return new File(context.getFilesDir(), "sessions");
    }

    private static File pointer(Context context) {
        return new File(sessions(context), "current");
    }

    /** The running capture's directory, or null when none runs. */
    static File current(Context context) throws IOException {
        File pointer = pointer(context);
        return pointer.isFile() ? new File(sessions(context), read(pointer)) : null;
    }

    static String mark(Context context) throws IOException {
        synchronized (Recorder.class) {
            File session = current(context);
            if (session == null) {
                throw new IOException("No capture is running.");
            }
            File marks = new File(session, "marks.txt");
            int n = lines(marks) + 1;
            Recorder.append(marks, "MARK " + n);
            Recorder.append(new File(session, "events.txt"), "MARK " + n);
            return "Marked the logs (mark " + n + ").";
        }
    }

    /** Also restarts the recorder if Android stopped it while the capture runs. */
    @Override
    public Map<String, String> status() throws IOException {
        Map<String, String> state = new HashMap<>();
        File session = current(context);
        if (session == null) {
            state.put("state", "idle");
            return state;
        }
        Recorder.start(context);
        state.put("state", "running");
        state.put("scenario", read(new File(session, "scenario")));
        state.put("started", read(new File(session, "started")));
        state.put("size_kb", String.valueOf(size(session) / 1024));
        state.put("marks", String.valueOf(lines(new File(session, "marks.txt"))));
        return state;
    }

    @Override
    public String start(String scenario) throws IOException {
        if (current(context) != null) {
            throw new IOException("Already capturing. Tap Stop first.");
        }
        File session = newSession(scenario);
        write(new File(session, "start.txt"), DeviceState.snapshot(context));
        write(pointer(context), session.getName());
        Recorder.start(context, true);
        return "Capturing to " + session.getName();
    }

    @Override
    public String mark() throws IOException {
        return mark(context);
    }

    @Override
    public File stop(String scenario, String notes, boolean bugreport) throws IOException {
        File session = current(context);
        if (session == null) {
            throw new IOException("No capture is running.");
        }
        Files.delete(pointer(context).toPath());
        boolean hadLiveLogcat = Recorder.stopLogcat();
        Recorder.stop(context);
        if (hasReadLogs(context)) {
            File logcat = new File(session, "logcat.txt");
            if (!hadLiveLogcat) {
                // Keep any pre-reboot live logcat intact and dump the current boot separately.
                dumpLogcat(logcat.isFile() && logcat.length() > 0
                        ? new File(session, "logcat-end.txt") : logcat);
            } else if (!logcat.isFile() || logcat.length() == 0) {
                dumpLogcat(logcat);
            }
        }
        write(new File(session, "end.txt"), DeviceState.snapshot(context));
        write(new File(session, "notes.txt"), notes);
        return pack(session, scenario);
    }

    @Override
    public File collect(String scenario, String notes, boolean bugreport) throws IOException {
        if (current(context) != null) {
            throw new IOException("A capture is running. Stop it first.");
        }
        File session = newSession(scenario);
        write(new File(session, "now.txt"), DeviceState.snapshot(context));
        if (hasReadLogs(context)) {
            dumpLogcat(new File(session, "logcat.txt"));
        }
        write(new File(session, "notes.txt"), notes);
        return pack(session, scenario);
    }

    private static void dumpLogcat(File out) throws IOException {
        File err = new File(out.getParentFile(), "errors.txt");
        Process process = new ProcessBuilder("/system/bin/logcat", "-b", "all", "-d", "-v",
                "threadtime")
                .redirectOutput(out)
                .redirectError(ProcessBuilder.Redirect.appendTo(err))
                .start();
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                Recorder.append(err, "logcat dump timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private File newSession(String scenario) throws IOException {
        File base = sessions(context);
        File[] old = base.listFiles(File::isDirectory);
        if (old != null) {
            Arrays.sort(old, (a, b) -> b.getName().compareTo(a.getName()));
            for (int i = KEEP_SESSIONS - 1; i < old.length; i++) {
                deleteTree(old[i]);
            }
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File session = new File(base, stamp + "-" + scenario);
        if (!session.mkdirs()) {
            throw new IOException("Cannot create " + session);
        }
        write(new File(session, "scenario"), scenario);
        write(new File(session, "started"), String.valueOf(System.currentTimeMillis() / 1000));
        return session;
    }

    private File pack(File session, String scenario) throws IOException {
        File archive = Archives.create(context, scenario, ".zip");
        Archives.zip(session, archive);
        return archive;
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
    }

    private static void write(File file, String text) throws IOException {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static int lines(File file) throws IOException {
        if (!file.isFile()) {
            return 0;
        }
        try (Stream<String> lines = Files.lines(file.toPath())) {
            return (int) lines.count();
        }
    }

    private static long size(File file) {
        File[] children = file.listFiles();
        if (children == null) {
            return file.length();
        }
        long total = 0;
        for (File child : children) {
            total += size(child);
        }
        return total;
    }

    private static void deleteTree(File file) throws IOException {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        Files.delete(file.toPath());
    }
}
