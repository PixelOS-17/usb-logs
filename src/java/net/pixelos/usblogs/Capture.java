package net.pixelos.usblogs;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/** One way to capture logs. Messages are shown to the tester as they are. */
interface Capture {
    /**
     * The capture's state as key=value pairs: state (idle, running or interrupted) and, unless
     * idle, scenario, started (epoch seconds), size_kb and marks.
     */
    Map<String, String> status() throws IOException, InterruptedException;

    String start(String scenario) throws IOException, InterruptedException;

    String mark() throws IOException, InterruptedException;

    /** Ends the running capture and returns its archive. */
    File stop(String scenario, String notes, boolean bugreport)
            throws IOException, InterruptedException;

    /** Collects logs of a problem that already happened and returns their archive. */
    File collect(String scenario, String notes, boolean bugreport)
            throws IOException, InterruptedException;
}
