package net.pixelos.usblogs;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.TypedArray;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
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

    private static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";

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
    private volatile Capture capture;
    /** The last Capture.status(), empty when unknown. */
    private Map<String, String> state = Collections.emptyMap();
    private int pending;

    private RadioGroup accessGroup;
    private RadioGroup scenarioGroup;
    private TextView accessState;
    private TextView steps;
    private EditText notes;
    private CheckBox bugreport;
    private TextView waiting;
    private Button allow;
    private Button takeBugreport;
    private Button start;
    private Button mark;
    private Button stop;
    private Button collect;
    private Button again;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        access = Access.valueOf(prefs.getString("access", defaultAccess().name()));
        capture = access.open(this);

        TextView intro = new TextView(this);
        intro.setText(INTRO);

        accessGroup = new RadioGroup(this);
        accessGroup.setId(ACCESS_GROUP_ID);
        addRadio(accessGroup, FIRST_ACCESS_ID + Access.ROOT.ordinal(), "Root");
        addRadio(accessGroup, FIRST_ACCESS_ID + Access.SHIZUKU.ordinal(), "Shizuku, no root needed");
        addRadio(accessGroup, FIRST_ACCESS_ID + Access.NONE.ordinal(),
                "Neither: fewer logs, plus a system bug report");
        accessGroup.check(FIRST_ACCESS_ID + access.ordinal());
        accessGroup.setOnCheckedChangeListener((group, id) -> {
            access = Access.values()[id - FIRST_ACCESS_ID];
            capture = access.open(this);
            prefs.edit().putString("access", access.name()).apply();
            bugreport.setChecked(access == Access.SHIZUKU);
            refresh();
        });

        accessState = new TextView(this);
        allow = secondaryButton("Allow Shizuku access", v -> {
            try {
                Shizuku.requestPermission();
            } catch (IOException e) {
                status.setText(reason(e));
            }
        });
        takeBugreport = secondaryButton("Take a bug report", v -> openBugreport());
        waiting = new TextView(this);

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

        start = button("Start capture", v -> {
            String scenario = selected().id;
            if (access == Access.NONE) {
                askForNotifications();
            }
            Capture current = capture;
            perform("Starting", () -> current.start(scenario));
        });
        mark = secondaryButton("Mark: the problem is happening now", v -> {
            Capture current = capture;
            perform("Marking", current::mark);
        });
        stop = button("Stop and share", v -> {
            Scenario scenario = scenario(state.get("scenario"));
            String text = notesText(scenario);
            boolean report = includeBugreport();
            Capture current = capture;
            perform(packingLabel("Stopping"),
                    () -> share(Archives.finish(this, current.stop(scenario.id, text, report))));
        });
        collect = button("Collect now and share", v -> {
            Scenario scenario = selected();
            String text = notesText(scenario);
            boolean report = includeBugreport();
            Capture current = capture;
            perform(packingLabel("Collecting"),
                    () -> share(Archives.finish(this, current.collect(scenario.id, text, report))));
        });
        again = secondaryButton("Share last logs", v -> {
            File[] archives = Archives.list(this);
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
        content.addView(takeBugreport);
        content.addView(waiting);
        for (Button button : new Button[] {start, mark, stop, collect, again}) {
            content.addView(button);
        }
        content.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(content);
        setContentView(scroll);

        if (savedInstanceState == null) {
            addSharedBugreport(getIntent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        addSharedBugreport(intent);
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

    private Access defaultAccess() {
        if (hasSu()) {
            return Access.ROOT;
        }
        return installed(SHIZUKU_PACKAGE) ? Access.SHIZUKU : Access.NONE;
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

    private boolean installed(String packageName) {
        try {
            getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
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

    private Button secondaryButton(String label, View.OnClickListener listener) {
        Button button = new Button(this, null, android.R.attr.borderlessButtonStyle);
        button.setText(label);
        button.setOnClickListener(listener);
        return button;
    }

    private TextView heading(String text, int pad) {
        TypedArray attributes = obtainStyledAttributes(new int[] {android.R.attr.colorAccent});
        int accent = attributes.getColor(0, 0);
        attributes.recycle();
        TextView heading = new TextView(this);
        heading.setText(text);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setTextColor(accent);
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
        return access != Access.SHIZUKU || Shizuku.granted();
    }

    private boolean includeBugreport() {
        return access != Access.NONE && bugreport.isChecked();
    }

    private String packingLabel(String verb) {
        return verb + (includeBugreport()
                ? " and taking a bug report, which takes 2 to 3 minutes"
                : " and packing the logs");
    }

    private String notesText(Scenario scenario) {
        return "Problem: " + scenario.title + "\nRun with: " + access + "\n\n" + notes.getText();
    }

    private void askForNotifications() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 0);
        }
    }

    private void openBugreport() {
        if (Settings.Global.getInt(getContentResolver(),
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 0) {
            status.setText("Turn on Developer options first: open Settings > About phone and tap"
                    + " Build number seven times. Then tap Take a bug report again.");
            return;
        }
        status.setText("In Developer options, tap Bug report, then Full report. When the"
                + " notification says it is ready, tap it and pick PixelOS Logs.");
        startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
    }

    private void refresh() {
        accessState.setText(describeAccess());
        if (ready()) {
            perform("Checking for a running capture", () -> "");
        } else {
            state = Collections.emptyMap();
            updateControls();
        }
    }

    private String describeAccess() {
        switch (access) {
            case ROOT:
                return "Allow root access when your root manager asks.";
            case SHIZUKU:
                if (!installed(SHIZUKU_PACKAGE)) {
                    return "Install Shizuku, start it with wireless debugging, then come back.";
                }
                if (!Shizuku.running()) {
                    return "Start Shizuku in its app, then come back here.";
                }
                if (!Shizuku.granted()) {
                    return "Shizuku is running. Tap Allow Shizuku access.";
                }
                return "Shizuku is ready. Kernel logs and crash files come from the bug report.";
            default:
                return "Records the USB, charging, network, sound, camera and screen changes that"
                        + " any app can see. For full logs, add a system bug report.";
        }
    }

    private void updateControls() {
        boolean busy = pending > 0;
        String value = state.get("state");
        boolean idle = "idle".equals(value);
        boolean running = "running".equals(value);
        boolean active = running || "interrupted".equals(value);
        boolean ready = ready();

        for (RadioGroup group : new RadioGroup[] {accessGroup, scenarioGroup}) {
            for (int i = 0; i < group.getChildCount(); i++) {
                group.getChildAt(i).setEnabled(!busy && !active);
            }
        }
        show(allow, access == Access.SHIZUKU && Shizuku.running() && !Shizuku.granted());
        show(bugreport, access != Access.NONE);
        show(takeBugreport, access == Access.NONE);
        File[] reports = Archives.pending(this);
        show(waiting, reports.length > 0);
        if (reports.length > 0) {
            long bytes = 0;
            for (File report : reports) {
                bytes += report.length();
            }
            waiting.setText(String.format(Locale.US, "Bug report ready to add (%d file%s, %.1f MB)."
                    + " It goes into the next archive you share.", reports.length,
                    reports.length == 1 ? "" : "s", bytes / 1048576.0));
        }
        show(start, ready && !active);
        show(collect, ready && !active);
        show(mark, ready && running);
        show(stop, ready && active);
        show(again, Archives.list(this).length > 0);
        boolean chosen = selected() != null;
        start.setEnabled(!busy && idle && chosen);
        collect.setEnabled(!busy && idle && chosen);
        for (Button button : new Button[] {allow, takeBugreport, mark, stop, again}) {
            button.setEnabled(!busy);
        }
    }

    private static void show(View view, boolean visible) {
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void perform(String label, Step step) {
        pending++;
        updateControls();
        status.setText(label + "...");
        Capture current = capture;
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
            Map<String, String> result = Collections.emptyMap();
            try {
                result = current.status();
            } catch (IOException e) {
                if (!reason(e).equals(message)) {
                    message = message + "\n" + reason(e);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String text = (message.trim() + "\n\n" + describe(result)).trim();
            Map<String, String> next = result;
            runOnUiThread(() -> {
                pending--;
                state = next;
                updateControls();
                status.setText(text);
            });
        });
    }

    private static String reason(IOException e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
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

    /** Keeps bug reports shared from the system's bug report notification for the next archive. */
    private void addSharedBugreport(Intent intent) {
        List<Uri> uris = streams(intent);
        if (uris.isEmpty()) {
            return;
        }
        perform("Adding the bug report", () -> {
            File dir = Archives.pendingDir(this);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("Cannot create " + dir);
            }
            for (Uri uri : uris) {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) {
                        throw new IOException("Cannot read " + uri);
                    }
                    Files.copy(in, new File(dir, fileName(uri)).toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return "Bug report added. Pick what went wrong, describe it, then tap Collect now and"
                    + " share, or Stop and share if a capture is running.";
        });
    }

    @SuppressWarnings("deprecation")
    private static List<Uri> streams(Intent intent) {
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            return uris != null ? uris : Collections.emptyList();
        }
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            return uri != null ? Collections.singletonList(uri) : Collections.emptyList();
        }
        return Collections.emptyList();
    }

    /** The shared file's own name, reduced to a plain file name inside the pending directory. */
    private String fileName(Uri uri) {
        String name = uri.getLastPathSegment();
        try (Cursor cursor = getContentResolver().query(uri,
                new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                name = cursor.getString(0);
            }
        }
        name = name == null ? "" : new File(name).getName();
        return name.isEmpty() || name.equals(".") || name.equals("..")
                ? "bugreport-" + System.currentTimeMillis() + ".zip" : name;
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
                .setType(Archives.type(archive))
                .putExtra(Intent.EXTRA_STREAM, LogProvider.uriFor(archive))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Send logs"));
    }
}
