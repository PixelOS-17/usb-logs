package net.pixelos.usblogs;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String STEPS =
            "1. Tap Start capture and allow root.\n"
            + "2. Connect the phone to the car and start Android Auto. Wait until it fails"
            + " or drops, then leave the cable in for another 30 seconds.\n"
            + "3. Unplug, come back here, tap Stop and share and send the file.\n\n"
            + "The capture keeps running if this app is closed.";

    private interface Step {
        String run() throws IOException, InterruptedException;
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private Button[] buttons;

    static File archiveDir(Context context) {
        return new File(context.getCacheDir(), "logs");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        TextView steps = new TextView(this);
        steps.setText(STEPS);

        Button start = new Button(this);
        start.setText("Start capture");
        start.setOnClickListener(v -> perform("Starting", () -> root("start")));

        Button stop = new Button(this);
        stop.setText("Stop and share");
        stop.setOnClickListener(v -> perform("Stopping and packing logs", () -> {
            String stopped = root("stop");
            File archive = archive();
            runOnUiThread(() -> share(archive));
            return stopped + "Saved " + archive.getName() + " (" + archive.length() / 1024
                    + " KiB)";
        }));

        Button again = new Button(this);
        again.setText("Share last logs");
        again.setOnClickListener(v -> {
            File archive = latestArchive();
            if (archive == null) {
                status.setText("No logs saved yet.");
                return;
            }
            share(archive);
        });

        status = new TextView(this);
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextIsSelectable(true);
        status.setPadding(0, pad, 0, 0);

        buttons = new Button[] {start, stop, again};

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad, pad, pad);
        content.addView(steps);
        for (Button button : buttons) {
            content.addView(button);
        }
        content.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(content);
        setContentView(scroll);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdown();
    }

    private void perform(String label, Step step) {
        setBusy(true);
        status.setText(label + "...");
        worker.execute(() -> {
            String result;
            try {
                result = step.run();
            } catch (IOException e) {
                result = e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                result = "Interrupted.";
            }
            String text = result;
            runOnUiThread(() -> {
                status.setText(text);
                setBusy(false);
            });
        });
    }

    private void setBusy(boolean busy) {
        for (Button button : buttons) {
            button.setEnabled(!busy);
        }
    }

    private ProcessBuilder su(String command) throws IOException {
        File script = new File(getFilesDir(), "capture.sh");
        try (InputStream in = getAssets().open("capture.sh")) {
            Files.copy(in, script.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return new ProcessBuilder("su", "-c", "sh " + script.getAbsolutePath() + " " + command);
    }

    private String root(String command) throws IOException, InterruptedException {
        Process process = su(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IOException("Root command '" + command + "' failed:\n" + output);
        }
        return output;
    }

    private File archive() throws IOException, InterruptedException {
        File dir = archiveDir(this);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create " + dir);
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File archive = new File(dir, "usb-logs-" + stamp + ".tar.gz");
        File errors = new File(dir, "archive-errors.txt");
        Process process = su("archive").redirectOutput(archive).redirectError(errors).start();
        if (process.waitFor() != 0) {
            String message = new String(Files.readAllBytes(errors.toPath()), StandardCharsets.UTF_8);
            Files.delete(archive.toPath());
            throw new IOException("Packing logs failed:\n" + message);
        }
        return archive;
    }

    private File latestArchive() {
        File[] archives = archiveDir(this).listFiles((dir, name) -> name.endsWith(".tar.gz"));
        if (archives == null) {
            return null;
        }
        File latest = null;
        for (File archive : archives) {
            if (latest == null || archive.lastModified() > latest.lastModified()) {
                latest = archive;
            }
        }
        return latest;
    }

    private void share(File archive) {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("application/gzip")
                .putExtra(Intent.EXTRA_STREAM, LogProvider.uriFor(archive))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Send USB logs"));
    }
}
