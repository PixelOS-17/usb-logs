package net.pixelos.usblogs;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String INTRO =
            "Pick what went wrong, tap Start capture and make the problem happen. Then come back"
            + " and tap Stop and share. If the problem already happened, tap Collect now"
            + " instead. A capture keeps running when this app is closed.\n\n"
            + "Logs can contain app names, network names and phone numbers. Only send them to"
            + " the developer who asked for them.";

    // Root and the shell user get separate directories, so neither trips over files the
    // other owns. The script is replaced by rename so a running capture keeps its own copy.
    private static final String RUN_SCRIPT = "d=/data/local/tmp/pixelos-logs-$(id -u)"
            + " && mkdir -p $d && cat > $d/capture.sh.new && mv $d/capture.sh.new $d/capture.sh"
            + " && exec sh $d/capture.sh ";

    private static final int KEEP_ARCHIVES = 5;

    private static final class Scenario {
        final String id;
        final String title;
        final String steps;

        Scenario(String id, String title, String steps) {
            this.id = id;
            this.title = title;
            this.steps = steps;
        }
    }

    // The ids must match the scenarios in capture.sh.
    private static final Scenario[] SCENARIOS = {
        new Scenario("reboot", "Random reboot, freeze or boot problem",
                "Start the capture and keep using the phone until it reboots or freezes. When it"
                + " is back, open this app and tap Stop and share. If it already rebooted, tap"
                + " Collect now as soon as it boots."),
        new Scenario("crash", "App crash or \"isn't responding\"",
                "Open the app and repeat what makes it crash or freeze. Write the app's name in"
                + " the description."),
        new Scenario("usb", "USB, Android Auto or file transfer",
                "Connect the cable and start Android Auto or file transfer. Wait until it fails or"
                + " drops, then leave the cable in for another 30 seconds before unplugging."),
        new Scenario("camera", "Camera",
                "Open the camera and repeat what fails. Write which camera app, mode and lens you"
                + " used."),
        new Scenario("audio", "Bluetooth or sound",
                "For Bluetooth, first set Developer options > Enable Bluetooth HCI snoop log to"
                + " Enabled and turn Bluetooth off and on. Then repeat the problem: pairing, music,"
                + " calls, microphone or speaker."),
        new Scenario("network", "Wi-Fi, mobile data, calls or SMS",
                "Repeat the problem: connect to the network, make the call or send the message."
                + " Write which SIM slot and carrier you use."),
        new Scenario("battery", "Battery drain, charging or heat",
                "Start the capture and use the phone normally for at least an hour, or plug in the"
                + " charger if charging is the problem. Then tap Stop and share."),
        new Scenario("display", "Screen, touch, fingerprint or face unlock",
                "Repeat the problem: lock and unlock, touch the part of the screen that fails, or"
                + " use the fingerprint sensor or face unlock."),
        new Scenario("other", "Something else",
                "Make the problem happen, then describe what you did and what went wrong."),
    };

    private static final int ACCESS_GROUP_ID = 1;
    private static final int SCENARIO_GROUP_ID = 2;
    private static final int NOTES_ID = 3;
    private static final int BUGREPORT_ID = 4;
    private static final int FIRST_ACCESS_ID = 10;
    private static final int FIRST_SCENARIO_ID = 100;

    private interface Step {
        String run() throws IOException, InterruptedException;
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private volatile Access access;
    /** The key=value lines of the last "capture.sh status", empty when unknown. */
    private Map<String, String> capture = Collections.emptyMap();
    private int pending;

    private RadioGroup accessGroup;
    private RadioGroup scenarioGroup;
    private TextView accessState;
    private TextView steps;
    private EditText notes;
    private CheckBox bugreport;
    private Button allow;
    private Button start;
    private Button mark;
    private Button stop;
    private Button collect;
    private Button again;
    private TextView status;

    static File archiveDir(Context context) {
        return new File(context.getCacheDir(), "logs");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        access = Access.valueOf(prefs.getString("access",
                (hasSu() ? Access.ROOT : Access.SHIZUKU).name()));

        TextView intro = new TextView(this);
        intro.setText(INTRO);

        accessGroup = new RadioGroup(this);
        accessGroup.setId(ACCESS_GROUP_ID);
        addRadio(accessGroup, FIRST_ACCESS_ID + Access.ROOT.ordinal(), "Root");
        addRadio(accessGroup, FIRST_ACCESS_ID + Access.SHIZUKU.ordinal(), "Shizuku, no root needed");
        accessGroup.check(FIRST_ACCESS_ID + access.ordinal());
        accessGroup.setOnCheckedChangeListener((group, id) -> {
            access = Access.values()[id - FIRST_ACCESS_ID];
            prefs.edit().putString("access", access.name()).apply();
            bugreport.setChecked(access == Access.SHIZUKU);
            refresh();
        });

        accessState = new TextView(this);

        allow = new Button(this);
        allow.setText("Allow Shizuku access");
        allow.setOnClickListener(v -> {
            try {
                Shizuku.requestPermission();
            } catch (IOException e) {
                status.setText(reason(e));
            }
        });

        scenarioGroup = new RadioGroup(this);
        scenarioGroup.setId(SCENARIO_GROUP_ID);
        for (int i = 0; i < SCENARIOS.length; i++) {
            addRadio(scenarioGroup, FIRST_SCENARIO_ID + i, SCENARIOS[i].title);
        }
        steps = new TextView(this);
        scenarioGroup.setOnCheckedChangeListener((group, id) -> {
            steps.setText(selected().steps);
            updateControls();
        });

        notes = new EditText(this);
        notes.setId(NOTES_ID);
        notes.setHint("What happened, and when?");
        notes.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        notes.setMinLines(3);
        notes.setGravity(Gravity.TOP | Gravity.START);

        bugreport = new CheckBox(this);
        bugreport.setId(BUGREPORT_ID);
        bugreport.setText("Add a full bug report. It takes 2 to 3 minutes and is the only way to"
                + " get kernel logs and crash files with Shizuku.");
        bugreport.setChecked(access == Access.SHIZUKU);

        start = button("Start capture", v -> perform("Starting",
                () -> run("start " + selected().id)));
        mark = button("Mark: the problem is happening now", v -> perform("Marking",
                () -> run("mark")));
        stop = button("Stop and share", v -> {
            String scenario = capture.get("scenario");
            String args = notesArg(scenario(scenario)) + bugreportArg();
            perform(packingLabel("Stopping"), () -> {
                String stopped = run("stop " + args);
                return stopped + share(archive(scenario));
            });
        });
        collect = button("Collect now and share", v -> {
            Scenario scenario = selected();
            String args = scenario.id + " " + notesArg(scenario) + bugreportArg();
            perform(packingLabel("Collecting"), () -> {
                String collected = run("collect " + args);
                return collected + share(archive(scenario.id));
            });
        });
        again = button("Share last logs", v -> {
            File[] archives = archives();
            if (archives.length > 0) {
                startShare(archives[0]);
            }
        });

        status = new TextView(this);
        status.setTypeface(Typeface.MONOSPACE);
        status.setTextIsSelectable(true);
        status.setPadding(0, pad, 0, 0);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad, pad, pad);
        content.addView(intro);
        content.addView(heading("Run with", pad));
        content.addView(accessGroup);
        content.addView(accessState);
        content.addView(allow);
        content.addView(heading("What went wrong?", pad));
        content.addView(scenarioGroup);
        content.addView(steps);
        content.addView(notes);
        content.addView(bugreport);
        for (Button button : new Button[] {start, mark, stop, collect, again}) {
            content.addView(button);
        }
        content.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(content);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Shizuku.setListener(this::refresh);
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Shizuku.setListener(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdown();
    }

    private static boolean hasSu() {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(":")) {
            if (new File(dir, "su").exists()) {
                return true;
            }
        }
        return false;
    }

    private void addRadio(RadioGroup group, int id, String label) {
        RadioButton radio = new RadioButton(this);
        radio.setId(id);
        radio.setText(label);
        group.addView(radio);
    }

    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(listener);
        return button;
    }

    private TextView heading(String text, int pad) {
        TextView heading = new TextView(this);
        heading.setText(text);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setPadding(0, pad, 0, 0);
        return heading;
    }

    private Scenario selected() {
        int id = scenarioGroup.getCheckedRadioButtonId();
        return id == View.NO_ID ? null : SCENARIOS[id - FIRST_SCENARIO_ID];
    }

    private static Scenario scenario(String id) {
        for (Scenario scenario : SCENARIOS) {
            if (scenario.id.equals(id)) {
                return scenario;
            }
        }
        throw new IllegalArgumentException("Unknown scenario " + id);
    }

    private boolean ready() {
        return access == Access.ROOT || Shizuku.granted();
    }

    private String packingLabel(String verb) {
        return verb + (bugreport.isChecked()
                ? " and taking a bug report, which takes 2 to 3 minutes"
                : " and packing the logs");
    }

    /** The tester's description for capture.sh, base64 encoded so it is safe in a command. */
    private String notesArg(Scenario scenario) {
        String text = "Problem: " + scenario.title + "\nRun with: " + access + "\n\n"
                + notes.getText();
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private String bugreportArg() {
        return bugreport.isChecked() ? " bugreport" : "";
    }

    private void refresh() {
        accessState.setText(describeAccess());
        if (ready()) {
            perform("Checking for a running capture", () -> "");
        } else {
            capture = Collections.emptyMap();
            updateControls();
        }
    }

    private String describeAccess() {
        if (access == Access.ROOT) {
            return "Allow root access when your root manager asks.";
        }
        if (!Shizuku.running()) {
            return "Shizuku is not running. Install the Shizuku app, start it with wireless"
                    + " debugging, then come back here.";
        }
        if (!Shizuku.granted()) {
            return "Shizuku is running. Tap Allow Shizuku access.";
        }
        return "Shizuku is ready. Without root the kernel log comes from the bug report.";
    }

    private void updateControls() {
        boolean busy = pending > 0;
        String state = capture.get("state");
        boolean idle = "idle".equals(state);
        boolean running = "running".equals(state);
        boolean active = running || "interrupted".equals(state);
        boolean chosen = selected() != null;
        boolean ready = ready();

        for (RadioGroup group : new RadioGroup[] {accessGroup, scenarioGroup}) {
            for (int i = 0; i < group.getChildCount(); i++) {
                group.getChildAt(i).setEnabled(!busy && !active);
            }
        }
        allow.setVisibility(access == Access.SHIZUKU && Shizuku.running() && !Shizuku.granted()
                ? View.VISIBLE : View.GONE);
        start.setEnabled(!busy && ready && idle && chosen);
        collect.setEnabled(!busy && ready && idle && chosen);
        mark.setEnabled(!busy && ready && running);
        stop.setEnabled(!busy && ready && active);
        again.setEnabled(!busy && archives().length > 0);
    }

    private void perform(String label, Step step) {
        pending++;
        updateControls();
        status.setText(label + "...");
        worker.execute(() -> {
            String message;
            try {
                message = step.run();
            } catch (IOException e) {
                message = reason(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                message = "Interrupted.";
            }
            Map<String, String> state = Collections.emptyMap();
            try {
                state = parse(run("status"));
            } catch (IOException e) {
                if (!reason(e).equals(message)) {
                    message = message + "\n" + reason(e);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String text = (message.trim() + "\n\n" + describe(state)).trim();
            Map<String, String> result = state;
            runOnUiThread(() -> {
                pending--;
                capture = result;
                updateControls();
                status.setText(text);
            });
        });
    }

    private static String reason(IOException e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    private static Map<String, String> parse(String output) {
        Map<String, String> values = new HashMap<>();
        for (String line : output.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                values.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        return values;
    }

    private static String describe(Map<String, String> state) {
        String value = state.get("state");
        if (value == null) {
            return "";
        }
        if (value.equals("idle")) {
            return "No capture is running.";
        }
        String title = scenario(state.get("scenario")).title;
        if (value.equals("interrupted")) {
            return "The \"" + title + "\" capture was cut off by a reboot. Tap Stop and share to"
                    + " pack what it caught and the logs of the crash.";
        }
        String since = new SimpleDateFormat("HH:mm", Locale.US)
                .format(new Date(Long.parseLong(state.get("started")) * 1000));
        return String.format(Locale.US, "Capturing \"%s\" since %s: %.1f MB, %s marks.", title,
                since, Long.parseLong(state.get("size_kb")) / 1024.0, state.get("marks"));
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
        Access.Command command = access.start(RUN_SCRIPT + args);
        try (OutputStream stdin = command.stdin(); InputStream script = getAssets().open("capture.sh")) {
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

    private File archive(String scenario) throws IOException, InterruptedException {
        File dir = archiveDir(this);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create " + dir);
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File archive = new File(dir, "pixelos-logs-" + scenario + "-" + stamp + ".tar.gz");
        try (OutputStream out = new FileOutputStream(archive)) {
            exec("archive", out);
        } catch (IOException | InterruptedException e) {
            Files.deleteIfExists(archive.toPath());
            throw e;
        }
        File[] archives = archives();
        for (int i = KEEP_ARCHIVES; i < archives.length; i++) {
            Files.delete(archives[i].toPath());
        }
        return archive;
    }

    /** Saved archives, newest first. */
    private File[] archives() {
        File[] archives = archiveDir(this).listFiles((dir, name) -> name.endsWith(".tar.gz"));
        if (archives == null) {
            return new File[0];
        }
        Arrays.sort(archives, Comparator.comparingLong(File::lastModified).reversed());
        return archives;
    }

    private String share(File archive) {
        runOnUiThread(() -> {
            notes.setText("");
            startShare(archive);
        });
        return "Saved " + archive.getName() + " (" + archive.length() / 1024 + " KiB)";
    }

    private void startShare(File archive) {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("application/gzip")
                .putExtra(Intent.EXTRA_STREAM, LogProvider.uriFor(archive))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Send logs"));
    }
}
