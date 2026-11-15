package com.herrcult69.metm;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Timestamped, tagged console logger shared by Alice, Trudy and Bob. */
final class Log {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private Log() {
    }

    static void info(String tag, String msg) {
        System.out.println(line(tag, msg));
    }

    static void error(String tag, String msg) {
        System.err.println(line(tag, msg));
    }

    private static String line(String tag, String msg) {
        return LocalTime.now().format(FMT) + " [" + tag + "] " + msg;
    }
}
