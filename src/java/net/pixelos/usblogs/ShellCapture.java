package net.pixelos.usblogs;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** Captures with capture.sh, run as root or as the shell user. */
final class ShellCapture implements Capture {
    // Root and the shell user get separate directories, so neither trips over files the
    // other owns. The script is replaced by rename so a running capture keeps its own copy.
    private static final String RUN_SCRIPT = "d=/data/local/tmp/pixelos-logs-$(id -u)"
            + " && mkdir -p $d && cat > $d/capture.sh.new && mv $d/capture.sh.new $d/capture.sh"
            + " && exec sh $d/capture.sh ";

    interface Command {
        OutputStream stdin();

        InputStream stdout();

        InputStream stderr();

        int waitFor() throws IOException, InterruptedException;
    }

    interface Shell {
        Command start(String command) throws IOException;
    }

    private final Context context;
    private final Shell shell;

    ShellCapture(Context context, Shell shell) {
        this.context = context.getApplicationContext();
        this.shell = shell;
    }

    @Override
    public Map<String, String> status() throws IOException, InterruptedException {
        Map<String, String> values = new HashMap<>();
        for (String line : run("status").split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                values.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        return values;
    }

    @Override
    public String start(String scenario) throws IOException, InterruptedException {
        return run("start " + scenario);
    }

    @Override
    public String mark() throws IOException, InterruptedException {
        return run("mark");
    }

    @Override
    public File stop(String scenario, String notes, boolean bugreport)
            throws IOException, InterruptedException {
        run("stop " + encode(notes) + (bugreport ? " bugreport" : ""));
        return archive(scenario);
    }

    @Override
    public File collect(String scenario, String notes, boolean bugreport)
            throws IOException, InterruptedException {
        run("collect " + scenario + " " + encode(notes) + (bugreport ? " bugreport" : ""));
        return archive(scenario);
    }

    /** Base64 keeps the tester's text out of the shell's parsing. */
    private static String encode(String notes) {
        return Base64.getEncoder().encodeToString(notes.getBytes(StandardCharsets.UTF_8));
    }

    private File archive(String scenario) throws IOException, InterruptedException {
        File archive = Archives.create(context, scenario, ".tar.gz");
        try (OutputStream out = new FileOutputStream(archive)) {
            exec("archive", out);
        } catch (IOException | InterruptedException e) {
            Files.deleteIfExists(archive.toPath());
            throw e;
        }
        return archive;
    }

    /** Runs capture.sh and returns its output, which includes its error messages. */
    private String run(String args) throws IOException, InterruptedException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            exec(args + " 2>&1", out);
        } catch (IOException e) {
            String output = out.toString(StandardCharsets.UTF_8).trim();
            throw output.isEmpty() ? e : new IOException(output, e);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Runs capture.sh with its stdout copied to sink. */
    private void exec(String args, OutputStream sink) throws IOException, InterruptedException {
        Command command = shell.start(RUN_SCRIPT + args);
        try (OutputStream stdin = command.stdin();
                InputStream script = context.getAssets().open("capture.sh")) {
            script.transferTo(stdin);
        }
        try (InputStream stdout = command.stdout()) {
            stdout.transferTo(sink);
        }
        String errors;
        try (InputStream stderr = command.stderr()) {
            errors = new String(stderr.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        int exit = command.waitFor();
        if (exit != 0) {
            throw new IOException(errors.isEmpty() ? "capture.sh " + args + " failed (" + exit + ")"
                    : errors);
        }
    }
}
