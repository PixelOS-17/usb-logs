package net.pixelos.usblogs;

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Which privileges a capture runs with. */
enum Access {
    ROOT {
        @Override
        Capture open(Context context) {
            return new ShellCapture(context, Access::su);
        }
    },

    /** Runs as the shell user, which can read logcat, dumpsys and bug reports but not dmesg. */
    SHIZUKU {
        @Override
        Capture open(Context context) {
            return new ShellCapture(context,
                    command -> Shizuku.start(new String[] {"/system/bin/sh", "-c", command}));
        }
    },

    /** Records only what any app may see, plus system bug reports the tester shares in. */
    NONE {
        @Override
        Capture open(Context context) {
            return new AppCapture(context);
        }
    };

    abstract Capture open(Context context);

    private static ShellCapture.Command su(String command) throws IOException {
        Process process;
        try {
            process = new ProcessBuilder("su", "-c", command).start();
        } catch (IOException e) {
            throw new IOException("This phone has no su. Pick Shizuku or no root instead.", e);
        }
        return new ShellCapture.Command() {
            @Override
            public OutputStream stdin() {
                return process.getOutputStream();
            }

            @Override
            public InputStream stdout() {
                return process.getInputStream();
            }

            @Override
            public InputStream stderr() {
                return process.getErrorStream();
            }

            @Override
            public int waitFor() throws InterruptedException {
                return process.waitFor();
            }
        };
    }
}
