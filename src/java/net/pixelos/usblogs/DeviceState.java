package net.pixelos.usblogs;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.biometrics.BiometricManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.display.DisplayManager;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.view.Display;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** A text snapshot of what any app may read about the phone, for captures without root. */
final class DeviceState {
    static final String USB_STATE = "android.hardware.usb.action.USB_STATE";

    // Versions that often matter in reports. Each needs a <queries> entry in the manifest.
    private static final String[] PACKAGES = {
        "com.google.android.projection.gearhead",
        "com.google.android.gms",
        "com.android.vending",
        "moe.shizuku.privileged.api",
    };

    private static final String[] GLOBAL_SETTINGS = {
        Settings.Global.ADB_ENABLED,
        Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
        Settings.Global.AIRPLANE_MODE_ON,
        Settings.Global.BLUETOOTH_ON,
        Settings.Global.WIFI_ON,
        Settings.Global.DATA_ROAMING,
        Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
        Settings.Global.AUTO_TIME,
    };

    private interface Section {
        void write(StringBuilder out) throws Exception;
    }

    private DeviceState() {
    }

    static String snapshot(Context context) {
        StringBuilder out = new StringBuilder();
        section(out, "time", o -> time(context, o));
        section(out, "build", DeviceState::build);
        section(out, "settings", o -> settings(context, o));
        section(out, "memory and storage", o -> memory(context, o));
        section(out, "battery and power", o -> power(context, o));
        section(out, "usb", o -> usb(context, o));
        section(out, "displays", o -> displays(context, o));
        section(out, "cameras", o -> cameras(context, o));
        section(out, "audio", o -> audio(context, o));
        section(out, "network", o -> network(context, o));
        section(out, "telephony", o -> telephony(context, o));
        section(out, "biometrics", o -> biometrics(context, o));
        section(out, "sensors", o -> sensors(context, o));
        section(out, "packages", o -> packages(context, o));
        section(out, "recent exits of this app", o -> exits(context, o));
        section(out, "properties this app can read", DeviceState::properties);
        return out.toString();
    }

    static String battery(Intent intent) {
        return String.format(Locale.US,
                "level=%d/%d status=%d plugged=%d health=%d temp=%.1fC voltage=%dmV",
                intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
                intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1),
                intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1),
                intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1),
                intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0,
                intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1));
    }

    @SuppressWarnings("deprecation")
    static String extras(Intent intent) {
        Bundle extras = intent.getExtras();
        if (extras == null) {
            return "";
        }
        List<String> keys = new ArrayList<>(extras.keySet());
        Collections.sort(keys);
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            out.append(out.length() > 0 ? " " : "").append(key).append('=').append(extras.get(key));
        }
        return out.toString();
    }

    static String network(NetworkCapabilities caps) {
        StringBuilder out = new StringBuilder("transports=");
        int[] transports = {NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_CELLULAR,
                NetworkCapabilities.TRANSPORT_ETHERNET, NetworkCapabilities.TRANSPORT_BLUETOOTH,
                NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_USB};
        String[] names = {"wifi", "cellular", "ethernet", "bluetooth", "vpn", "usb"};
        for (int i = 0; i < transports.length; i++) {
            if (caps.hasTransport(transports[i])) {
                out.append(names[i]).append(',');
            }
        }
        return out.append(" internet=")
                .append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                .append(" validated=")
                .append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                .append(" signal=").append(caps.getSignalStrength())
                .toString();
    }

    static String audioDevice(AudioDeviceInfo device) {
        return (device.isSink() ? "out" : "in") + " type=" + device.getType()
                + " name=" + device.getProductName() + " id=" + device.getId();
    }

    static String display(Display display) {
        Display.Mode mode = display.getMode();
        return display.getDisplayId() + " " + display.getName() + " state=" + display.getState()
                + " mode=" + mode.getPhysicalWidth() + "x" + mode.getPhysicalHeight()
                + "@" + mode.getRefreshRate() + " refresh=" + display.getRefreshRate();
    }

    // A snapshot keeps going when one API is restricted or missing on a build; the failure is
    // written into that section instead.
    private static void section(StringBuilder out, String title, Section section) {
        out.append("===== ").append(title).append('\n');
        try {
            section.write(out);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.append("<interrupted>\n");
        } catch (Exception e) {
            out.append("<failed: ").append(e).append(">\n");
        }
        out.append('\n');
    }

    private static void line(StringBuilder out, String key, Object value) {
        out.append(key).append('=').append(value).append('\n');
    }

    private static void time(Context context, StringBuilder out) {
        line(out, "now", new Date());
        line(out, "elapsed_realtime_s", SystemClock.elapsedRealtime() / 1000);
        line(out, "uptime_s", SystemClock.uptimeMillis() / 1000);
        line(out, "boot_count", Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.BOOT_COUNT, -1));
        line(out, "kernel", System.getProperty("os.version"));
    }

    private static void build(StringBuilder out) {
        line(out, "fingerprint", Build.FINGERPRINT);
        line(out, "display", Build.DISPLAY);
        line(out, "model", Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")");
        line(out, "android", Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
        line(out, "security_patch", Build.VERSION.SECURITY_PATCH);
        line(out, "type", Build.TYPE + " " + Build.TAGS);
        line(out, "soc", Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL);
        line(out, "radio", Build.getRadioVersion());
        line(out, "bootloader", Build.BOOTLOADER);
    }

    private static void settings(Context context, StringBuilder out) {
        ContentResolver resolver = context.getContentResolver();
        for (String key : GLOBAL_SETTINGS) {
            line(out, key, Settings.Global.getString(resolver, key));
        }
    }

    private static void memory(Context context, StringBuilder out) {
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        context.getSystemService(ActivityManager.class).getMemoryInfo(info);
        line(out, "ram_total_mb", info.totalMem >> 20);
        line(out, "ram_available_mb", info.availMem >> 20);
        line(out, "low_memory", info.lowMemory);
        StatFs data = new StatFs(Environment.getDataDirectory().getPath());
        line(out, "data_total_mb", data.getTotalBytes() >> 20);
        line(out, "data_free_mb", data.getAvailableBytes() >> 20);
    }

    private static void power(Context context, StringBuilder out) {
        Intent battery = context.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED);
        line(out, "battery", battery == null ? null : battery(battery));
        BatteryManager manager = context.getSystemService(BatteryManager.class);
        line(out, "charging", manager.isCharging());
        line(out, "current_now_ua",
                manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW));
        line(out, "current_average_ua",
                manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE));
        line(out, "charge_counter_uah",
                manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER));
        line(out, "charge_time_remaining_ms", manager.computeChargeTimeRemaining());
        PowerManager power = context.getSystemService(PowerManager.class);
        line(out, "thermal_status", power.getCurrentThermalStatus());
        line(out, "thermal_headroom_30s", power.getThermalHeadroom(30));
        line(out, "power_save", power.isPowerSaveMode());
        line(out, "device_idle", power.isDeviceIdleMode());
        line(out, "interactive", power.isInteractive());
    }

    private static void usb(Context context, StringBuilder out) {
        Intent state = context.registerReceiver(null, new IntentFilter(USB_STATE),
                Context.RECEIVER_NOT_EXPORTED);
        line(out, "usb_state", state == null ? null : extras(state));
        UsbManager usb = context.getSystemService(UsbManager.class);
        for (UsbDevice device : usb.getDeviceList().values()) {
            line(out, "host_device", String.format(Locale.US, "%s %04x:%04x class=%d",
                    device.getDeviceName(), device.getVendorId(), device.getProductId(),
                    device.getDeviceClass()));
        }
        UsbAccessory[] accessories = usb.getAccessoryList();
        if (accessories != null) {
            for (UsbAccessory accessory : accessories) {
                line(out, "accessory", accessory.getManufacturer() + " " + accessory.getModel()
                        + " " + accessory.getVersion());
            }
        }
    }

    private static void displays(Context context, StringBuilder out) {
        for (Display display : context.getSystemService(DisplayManager.class).getDisplays()) {
            line(out, "display", display(display));
            line(out, "modes", Arrays.toString(display.getSupportedModes()));
        }
    }

    private static void cameras(Context context, StringBuilder out) throws Exception {
        CameraManager manager = context.getSystemService(CameraManager.class);
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics camera = manager.getCameraCharacteristics(id);
            line(out, "camera " + id, "facing=" + camera.get(CameraCharacteristics.LENS_FACING)
                    + " level="
                    + camera.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                    + " capabilities=" + Arrays.toString(
                            camera.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES))
                    + " physical=" + camera.getPhysicalCameraIds()
                    + " focal_lengths=" + Arrays.toString(
                            camera.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS))
                    + " pixels=" + camera.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE));
        }
        line(out, "concurrent", manager.getConcurrentCameraIds());
    }

    private static void audio(Context context, StringBuilder out) {
        AudioManager audio = context.getSystemService(AudioManager.class);
        line(out, "mode", audio.getMode());
        line(out, "ringer_mode", audio.getRingerMode());
        line(out, "music_active", audio.isMusicActive());
        int[] streams = {AudioManager.STREAM_VOICE_CALL, AudioManager.STREAM_RING,
                AudioManager.STREAM_MUSIC, AudioManager.STREAM_ALARM,
                AudioManager.STREAM_NOTIFICATION};
        String[] names = {"call", "ring", "music", "alarm", "notification"};
        for (int i = 0; i < streams.length; i++) {
            line(out, "volume_" + names[i], audio.getStreamVolume(streams[i]) + "/"
                    + audio.getStreamMaxVolume(streams[i]));
        }
        AudioDeviceInfo communication = audio.getCommunicationDevice();
        line(out, "communication_device",
                communication == null ? null : audioDevice(communication));
        for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_ALL)) {
            line(out, "device", audioDevice(device));
        }
    }

    private static void network(Context context, StringBuilder out) {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        Network active = connectivity.getActiveNetwork();
        line(out, "active", active);
        if (active != null) {
            line(out, "capabilities", connectivity.getNetworkCapabilities(active));
            line(out, "link", connectivity.getLinkProperties(active));
        }
    }

    private static void telephony(Context context, StringBuilder out) {
        TelephonyManager telephony = context.getSystemService(TelephonyManager.class);
        line(out, "modems", telephony.getActiveModemCount());
        line(out, "phone_type", telephony.getPhoneType());
        line(out, "sim_state", telephony.getSimState());
        line(out, "sim_operator", telephony.getSimOperatorName());
        line(out, "network_operator", telephony.getNetworkOperatorName());
        line(out, "network_country", telephony.getNetworkCountryIso());
        line(out, "data_enabled", telephony.isDataEnabled());
    }

    private static void biometrics(Context context, StringBuilder out) {
        PackageManager packages = context.getPackageManager();
        line(out, "fingerprint_hardware",
                packages.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT));
        line(out, "face_hardware", packages.hasSystemFeature(PackageManager.FEATURE_FACE));
        BiometricManager biometrics = context.getSystemService(BiometricManager.class);
        line(out, "strong", biometrics.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG));
        line(out, "weak", biometrics.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_WEAK));
    }

    private static void sensors(Context context, StringBuilder out) {
        for (Sensor sensor : context.getSystemService(SensorManager.class)
                .getSensorList(Sensor.TYPE_ALL)) {
            line(out, "sensor", sensor.getStringType() + " " + sensor.getName() + " ("
                    + sensor.getVendor() + ") wake_up=" + sensor.isWakeUpSensor());
        }
    }

    private static void packages(Context context, StringBuilder out) {
        PackageManager manager = context.getPackageManager();
        for (String name : PACKAGES) {
            try {
                PackageInfo info = manager.getPackageInfo(name, 0);
                line(out, name, info.versionName + " (" + info.getLongVersionCode() + ")");
            } catch (PackageManager.NameNotFoundException e) {
                line(out, name, "not installed");
            }
        }
    }

    private static void exits(Context context, StringBuilder out) {
        for (ApplicationExitInfo exit : context.getSystemService(ActivityManager.class)
                .getHistoricalProcessExitReasons(null, 0, 5)) {
            line(out, "exit", new Date(exit.getTimestamp()) + " reason=" + exit.getReason()
                    + " " + exit.getDescription());
        }
    }

    private static void properties(StringBuilder out) throws Exception {
        Process process = new ProcessBuilder("/system/bin/getprop").redirectErrorStream(true)
                .start();
        out.append(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        process.waitFor();
    }
}
