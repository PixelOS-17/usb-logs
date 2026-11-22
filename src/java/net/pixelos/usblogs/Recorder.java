package net.pixelos.usblogs;

import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.hardware.camera2.CameraManager;
import android.hardware.display.DisplayManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.Display;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Writes what changes on the phone to the running AppCapture session's events.txt: USB,
 * charging, power, thermal, network, audio devices, camera use and displays.
 */
public final class Recorder extends Service {
    private static final String TAG = "PixelOSLogs";
    private static final String ACTION_MARK = "net.pixelos.usblogs.action.MARK";
    private static final String EXTRA_FOREGROUND = "foreground";
    private static final String CHANNEL = "capture";
    private static final int NOTIFICATION = 1;

    private static Process logcat;

    private HandlerThread thread;
    private Handler handler;
    private volatile File events;
    private final List<Runnable> cleanup = new ArrayList<>();
    /** The last line per source, so an unchanged state is not written again. */
    private final Map<String, String> last = new HashMap<>();

    static void start(Context context) {
        start(context, false);
    }

    static void start(Context context, boolean foreground) {
        Intent intent = new Intent(context, Recorder.class)
                .putExtra(EXTRA_FOREGROUND, foreground);
        context.startForegroundService(intent);
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, Recorder.class));
    }

    /** Starts streaming logcat into the session when READ_LOGS is granted and UI is foreground. */
    private static synchronized void startLogcat(File session) {
        if (logcat != null && logcat.isAlive()) {
            return;
        }
        File out = new File(session, "logcat.txt");
        File err = new File(session, "errors.txt");
        try {
            logcat = new ProcessBuilder("/system/bin/logcat", "-b", "all", "-v", "threadtime",
                    "-f", out.getPath(), "-r", "16384", "-n", "15")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(err))
                    .start();
        } catch (IOException e) {
            Log.e(TAG, "Cannot start logcat", e);
        }
    }

    /** Stops any running live logcat process synchronously. Returns true if one was alive. */
    static synchronized boolean stopLogcat() {
        Process process = logcat;
        logcat = null;
        if (process == null) {
            return false;
        }
        boolean alive = process.isAlive();
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        return alive;
    }

    /** Opens and closes the file per line, so a crash or reboot loses nothing written. */
    static synchronized void append(File file, String line) throws IOException {
        String stamp = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        try (Writer out = new FileWriter(file, true)) {
            out.write(stamp + " up=" + SystemClock.elapsedRealtime() / 1000 + "s " + line + "\n");
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("recorder");
        thread.start();
        handler = new Handler(thread.getLooper());
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Capture", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIFICATION, notification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } catch (ForegroundServiceStartNotAllowedException e) {
            // Opening the app starts the recorder again through AppCapture.status().
            Log.w(TAG, "Recorder cannot start from the background now", e);
            stopSelf();
            return START_NOT_STICKY;
        }
        File session;
        try {
            session = AppCapture.current(this);
        } catch (IOException e) {
            Log.e(TAG, "Cannot read the running capture", e);
            session = null;
        }
        if (session == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_MARK.equals(intent.getAction())) {
            handler.post(() -> {
                try {
                    AppCapture.mark(this);
                } catch (IOException e) {
                    Log.e(TAG, "Cannot mark the capture", e);
                }
            });
        }
        if (events == null) {
            events = new File(session, "events.txt");
            register();
        }
        if (intent != null && intent.getBooleanExtra(EXTRA_FOREGROUND, false)
                && AppCapture.hasReadLogs(this)) {
            startLogcat(session);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopLogcat();
        for (Runnable undo : cleanup) {
            undo.run();
        }
        cleanup.clear();
        handler.post(() -> record("RECORDER STOP"));
        thread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent mark = PendingIntent.getForegroundService(this, 1,
                new Intent(this, Recorder.class).setAction(ACTION_MARK),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentTitle("Capturing logs")
                .setContentText("Tap Mark when the problem happens.")
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_menu_edit), "Mark", mark)
                        .build())
                .build();
    }

    private void record(String line) {
        if (events == null) {
            return;
        }
        try {
            append(events, line);
        } catch (IOException e) {
            Log.e(TAG, "Cannot write " + events, e);
        }
    }

    private synchronized void changed(String source, String line) {
        if (!line.equals(last.put(source, line))) {
            record(line);
        }
    }

    private synchronized void forget(String source) {
        last.remove(source);
    }

    /** Runs on the main thread; every callback it registers runs on the recorder thread. */
    private void register() {
        record("RECORDER START boot_count="
                + Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1));
        Executor executor = handler::post;

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                String name = action.substring(action.lastIndexOf('.') + 1);
                String detail = Intent.ACTION_BATTERY_CHANGED.equals(action)
                        ? DeviceState.battery(intent) : DeviceState.extras(intent);
                changed(name, (name + " " + detail).trim());
            }
        };
        IntentFilter filter = new IntentFilter();
        for (String action : new String[] {Intent.ACTION_BATTERY_CHANGED, DeviceState.USB_STATE,
                Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED,
                Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF, Intent.ACTION_USER_PRESENT,
                Intent.ACTION_AIRPLANE_MODE_CHANGED, Intent.ACTION_HEADSET_PLUG,
                Intent.ACTION_SHUTDOWN, PowerManager.ACTION_POWER_SAVE_MODE_CHANGED,
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED}) {
            filter.addAction(action);
        }
        registerReceiver(receiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED);
        cleanup.add(() -> unregisterReceiver(receiver));

        PowerManager power = getSystemService(PowerManager.class);
        PowerManager.OnThermalStatusChangedListener thermal =
                status -> changed("thermal", "THERMAL status=" + status);
        power.addThermalStatusListener(executor, thermal);
        cleanup.add(() -> power.removeThermalStatusListener(thermal));

        ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
        ConnectivityManager.NetworkCallback network = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network net) {
                record("NETWORK available " + net);
            }

            @Override
            public void onLost(Network net) {
                record("NETWORK lost " + net);
                forget("network");
            }

            @Override
            public void onCapabilitiesChanged(Network net, NetworkCapabilities caps) {
                changed("network", "NETWORK " + net + " " + DeviceState.network(caps));
            }

            @Override
            public void onLinkPropertiesChanged(Network net, LinkProperties link) {
                changed("link", "LINK " + net + " interface=" + link.getInterfaceName());
            }
        };
        connectivity.registerDefaultNetworkCallback(network, handler);
        cleanup.add(() -> connectivity.unregisterNetworkCallback(network));

        AudioManager audio = getSystemService(AudioManager.class);
        AudioDeviceCallback devices = new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                for (AudioDeviceInfo device : added) {
                    record("AUDIO added " + DeviceState.audioDevice(device));
                }
            }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                for (AudioDeviceInfo device : removed) {
                    record("AUDIO removed " + DeviceState.audioDevice(device));
                }
            }
        };
        audio.registerAudioDeviceCallback(devices, handler);
        cleanup.add(() -> audio.unregisterAudioDeviceCallback(devices));
        AudioManager.OnModeChangedListener mode = m -> changed("mode", "AUDIO mode=" + m);
        audio.addOnModeChangedListener(executor, mode);
        cleanup.add(() -> audio.removeOnModeChangedListener(mode));

        CameraManager camera = getSystemService(CameraManager.class);
        CameraManager.AvailabilityCallback cameras = new CameraManager.AvailabilityCallback() {
            @Override
            public void onCameraAvailable(String id) {
                changed("camera" + id, "CAMERA " + id + " free");
            }

            @Override
            public void onCameraUnavailable(String id) {
                changed("camera" + id, "CAMERA " + id + " in use");
            }
        };
        camera.registerAvailabilityCallback(cameras, handler);
        cleanup.add(() -> camera.unregisterAvailabilityCallback(cameras));
        CameraManager.TorchCallback torch = new CameraManager.TorchCallback() {
            @Override
            public void onTorchModeChanged(String id, boolean enabled) {
                changed("torch" + id, "TORCH " + id + " on=" + enabled);
            }
        };
        camera.registerTorchCallback(torch, handler);
        cleanup.add(() -> camera.unregisterTorchCallback(torch));

        DisplayManager displays = getSystemService(DisplayManager.class);
        DisplayManager.DisplayListener display = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int id) {
                onDisplayChanged(id);
            }

            @Override
            public void onDisplayRemoved(int id) {
                record("DISPLAY " + id + " removed");
                forget("display" + id);
            }

            @Override
            public void onDisplayChanged(int id) {
                Display d = displays.getDisplay(id);
                if (d != null) {
                    changed("display" + id, "DISPLAY " + DeviceState.display(d));
                }
            }
        };
        displays.registerDisplayListener(display, handler);
        cleanup.add(() -> displays.unregisterDisplayListener(display));
        for (Display d : displays.getDisplays()) {
            display.onDisplayChanged(d.getDisplayId());
        }

        if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            TelephonyManager telephony = getSystemService(TelephonyManager.class);
            if (telephony != null) {
                TelephonyListener listener = new TelephonyListener();
                try {
                    telephony.registerTelephonyCallback(executor, listener);
                    cleanup.add(() -> telephony.unregisterTelephonyCallback(listener));
                } catch (SecurityException | UnsupportedOperationException e) {
                    record("TELEPHONY callback unavailable: " + e.getMessage());
                }
            }
        }
    }

    private final class TelephonyListener extends TelephonyCallback implements
            TelephonyCallback.ServiceStateListener,
            TelephonyCallback.SignalStrengthsListener,
            TelephonyCallback.DisplayInfoListener {
        @Override
        public void onServiceStateChanged(ServiceState state) {
            changed("tel_service", "TELEPHONY " + DeviceState.serviceState(state));
        }

        @Override
        public void onSignalStrengthsChanged(SignalStrength signal) {
            changed("tel_signal", "TELEPHONY " + DeviceState.signalStrength(signal));
        }

        @Override
        public void onDisplayInfoChanged(TelephonyDisplayInfo info) {
            changed("tel_display", "TELEPHONY " + DeviceState.telephonyDisplay(info));
        }
    }
}
