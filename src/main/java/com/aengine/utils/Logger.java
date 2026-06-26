package com.aengine.utils;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class Logger {
    public enum Level {
        TRACE(0, "\u001B[37m"), // White
        DEBUG(1, "\u001B[36m"), // Cyan
        INFO(2,  "\u001B[32m"), // Green
        WARN(3,  "\u001B[33m"), // Yellow
        ERROR(4, "\u001B[31m"); // Red

        final int priority;
        final String color;

        Level(int priority, String color) {
            this.priority = priority;
            this.color = color;
        }
    }

    public enum System {
        CORE("CORE"),
        WINDOW("WINDOW"),
        RENDERER("RENDERER"),
        SHADER("SHADER"),
        ASSET("ASSET");

        final String label;
        Level currentLevel = Level.DEBUG; 

        System(String label) {
            this.label = label;
        }

        public void setLevel(Level level) {
            this.currentLevel = level;
        }
    }

    private static final String RESET = "\u001B[0m";
    private static final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static void log(System sys, Level lvl, String msg, Object... args) {
        if (lvl.priority >= sys.currentLevel.priority) {
            String timestamp = LocalTime.now().format(timeFormatter);
            String formattedMsg = String.format(msg, args);
            java.lang.System.out.printf("[%s] %s%-5s%s [%-8s] %s%n", 
                timestamp, lvl.color, lvl.name(), RESET, sys.label, formattedMsg);
        }
    }

    public static void trace(System sys, String msg, Object... args) { log(sys, Level.TRACE, msg, args); }
    public static void debug(System sys, String msg, Object... args) { log(sys, Level.DEBUG, msg, args); }
    public static void info(System sys, String msg, Object... args)  { log(sys, Level.INFO, msg, args); }
    public static void warn(System sys, String msg, Object... args)  { log(sys, Level.WARN, msg, args); }
    public static void error(System sys, String msg, Object... args) { log(sys, Level.ERROR, msg, args); }
}
