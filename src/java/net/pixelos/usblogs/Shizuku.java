package net.pixelos.usblogs;

import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.Consumer;

/**
 * Client for the Shizuku server binder, protocol version 13. Transaction codes are
 * FIRST_CALL_TRANSACTION plus the ids in Shizuku-API's moe/shizuku/server/*.aidl.
 */
final class Shizuku {
    private static final String TAG = "PixelOSLogs";
    private static final String SERVICE = "moe.shizuku.server.IShizukuService";
    private static final String APPLICATION = "moe.shizuku.server.IShizukuApplication";
    private static final String PROCESS = "moe.shizuku.server.IRemoteProcess";
    private static final int API_VERSION = 13;

    private static final int NEW_PROCESS = IBinder.FIRST_CALL_TRANSACTION + 7;
    private static final int REQUEST_PERMISSION = IBinder.FIRST_CALL_TRANSACTION + 14;
    private static final int ATTACH_APPLICATION = IBinder.FIRST_CALL_TRANSACTION + 17;

    private static final int BIND_APPLICATION = IBinder.FIRST_CALL_TRANSACTION + 1;
    private static final int PERMISSION_RESULT = IBinder.FIRST_CALL_TRANSACTION + 2;

    private static final int GET_OUTPUT_STREAM = IBinder.FIRST_CALL_TRANSACTION;
    private static final int GET_INPUT_STREAM = IBinder.FIRST_CALL_TRANSACTION + 1;
    private static final int GET_ERROR_STREAM = IBinder.FIRST_CALL_TRANSACTION + 2;
    private static final int WAIT_FOR = IBinder.FIRST_CALL_TRANSACTION + 3;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile IBinder service;
    private static volatile boolean granted;
    private static volatile Runnable listener;

    /** Receives the server's replies to attachApplication and requestPermission. */
    private static final Binder APPLICATION_BINDER = new Binder(APPLICATION) {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            switch (code) {
                case BIND_APPLICATION:
                    data.enforceInterface(APPLICATION);
                    granted = readBundle(data).getBoolean("shizuku:attach-reply-permission-granted");
                    changed();
                    return true;
                case PERMISSION_RESULT:
                    data.enforceInterface(APPLICATION);
                    data.readInt();
                    granted = readBundle(data).getBoolean("shizuku:request-permission-reply-allowed");
                    changed();
                    return true;
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }
    };

    private Shizuku() {
    }

    /** Runs on the main thread whenever the server appears, dies or answers. */
    static void setListener(Runnable runnable) {
        listener = runnable;
    }

    static boolean running() {
        IBinder binder = service;
        return binder != null && binder.pingBinder();
    }

    static boolean granted() {
        return granted && running();
    }

    static void onBinderReceived(IBinder binder, String packageName) {
        if (running()) {
            return;
        }
        try {
            binder.linkToDeath(() -> {
                service = null;
                granted = false;
                changed();
            }, 0);
            call(binder, SERVICE, ATTACH_APPLICATION, data -> {
                Bundle args = new Bundle();
                args.putInt("shizuku:attach-api-version", API_VERSION);
                args.putString("shizuku:attach-package-name", packageName);
                data.writeStrongBinder(APPLICATION_BINDER);
                data.writeInt(1);
                args.writeToParcel(data, 0);
            }).recycle();
            service = binder;
        } catch (RemoteException | SecurityException | IllegalStateException e) {
            Log.w(TAG, "Shizuku did not accept attachApplication", e);
        }
        changed();
    }

    static void requestPermission() throws IOException {
        try {
            call(require(), SERVICE, REQUEST_PERMISSION, data -> data.writeInt(0)).recycle();
        } catch (RemoteException e) {
            throw new IOException("Shizuku stopped responding.", e);
        }
    }

    static ShellCapture.Command start(String[] command) throws IOException {
        IBinder binder = require();
        if (!granted) {
            throw new IOException("Tap Allow Shizuku access first.");
        }
        try {
            Parcel reply = call(binder, SERVICE, NEW_PROCESS, data -> {
                data.writeStringArray(command);
                data.writeStringArray(null);
                data.writeString(null);
            });
            try {
                return new RemoteCommand(reply.readStrongBinder());
            } finally {
                reply.recycle();
            }
        } catch (RemoteException e) {
            throw new IOException("Shizuku stopped responding.", e);
        } catch (SecurityException | IllegalStateException e) {
            throw new IOException("Shizuku refused to run the capture: " + e.getMessage(), e);
        }
    }

    private static IBinder require() throws IOException {
        IBinder binder = service;
        if (binder == null) {
            throw new IOException("Shizuku is not running. Start it in the Shizuku app.");
        }
        return binder;
    }

    private static void changed() {
        MAIN.post(() -> {
            Runnable runnable = listener;
            if (runnable != null) {
                runnable.run();
            }
        });
    }

    private static Bundle readBundle(Parcel data) {
        return data.readInt() != 0 ? Bundle.CREATOR.createFromParcel(data) : Bundle.EMPTY;
    }

    /** Sends one transaction and returns the reply, which the caller recycles. */
    private static Parcel call(IBinder binder, String descriptor, int code, Consumer<Parcel> args)
            throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            args.accept(data);
            binder.transact(code, data, reply, 0);
            reply.readException();
            return reply;
        } catch (RemoteException | RuntimeException e) {
            reply.recycle();
            throw e;
        } finally {
            data.recycle();
        }
    }

    private static final class RemoteCommand implements ShellCapture.Command {
        private final IBinder process;
        private final OutputStream stdin;
        private final InputStream stdout;
        private final InputStream stderr;

        RemoteCommand(IBinder process) throws RemoteException {
            this.process = process;
            stdin = new ParcelFileDescriptor.AutoCloseOutputStream(stream(GET_OUTPUT_STREAM));
            stdout = new ParcelFileDescriptor.AutoCloseInputStream(stream(GET_INPUT_STREAM));
            stderr = new ParcelFileDescriptor.AutoCloseInputStream(stream(GET_ERROR_STREAM));
        }

        private ParcelFileDescriptor stream(int code) throws RemoteException {
            Parcel reply = call(process, PROCESS, code, data -> { });
            try {
                if (reply.readInt() == 0) {
                    throw new RemoteException("Shizuku returned no stream");
                }
                return ParcelFileDescriptor.CREATOR.createFromParcel(reply);
            } finally {
                reply.recycle();
            }
        }

        @Override
        public OutputStream stdin() {
            return stdin;
        }

        @Override
        public InputStream stdout() {
            return stdout;
        }

        @Override
        public InputStream stderr() {
            return stderr;
        }

        @Override
        public int waitFor() throws IOException {
            try {
                Parcel reply = call(process, PROCESS, WAIT_FOR, data -> { });
                try {
                    return reply.readInt();
                } finally {
                    reply.recycle();
                }
            } catch (RemoteException e) {
                throw new IOException("Shizuku stopped while the capture ran.", e);
            }
        }
    }
}
