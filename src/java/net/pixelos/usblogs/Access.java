package net.pixelos.usblogs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** How capture.sh gets the privileges it needs. */
enum Access {
    ROOT {
        @Override
        Command start(String command) throws IOException {
            Process process;
            try {
                process = new ProcessBuilder("su", "-c", command).start();
            } catch (IOException e) {
                throw new IOException("This phone has no su. Root it, or pick Shizuku.", e);
            }
            return new Command() {
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
    },

    /** Runs as the shell user, which can read logcat, dumpsys and bug reports but not dmesg. */
    SHIZUKU {
        @Override
        Command start(String command) throws IOException {
            return Shizuku.start(new String[] {"/system/bin/sh", "-c", command});
        }
    };

    interface Command {
        OutputStream stdin();

        InputStream stdout();

        InputStream stderr();

        int waitFor() throws IOException, InterruptedException;
    }

    abstract Command start(String command) throws IOException;
}
