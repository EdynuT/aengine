package com.aengine.utils;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * The engine's console log.
 *
 * <p>Each line carries the time, the level (in colour), the subsystem, the file and line
 * that logged it, and the message:</p>
 * <pre>
 * [14:03:12.481] INFO  [RENDERER] [Renderer2D.java:69] Batch storage allocations verified.
 * </pre>
 *
 * <p>Messages use {@link String#format} placeholders: {@code Logger.info(Logger.System.CORE,
 * "Loaded %d entities", count)}. Each subsystem has its own minimum level, DEBUG by
 * default, so TRACE lines are dropped unless a subsystem is lowered with
 * {@link System#setLevel(Level)}. A dropped line costs only the level check (plus boxing
 * of primitive arguments); a printed one also walks the stack to find its caller, so keep
 * logging out of per-frame paths.</p>
 *
 * <p>Output goes to standard out. The colours are ANSI codes, which most terminals
 * understand.</p>
 */
public class Logger {

    private Logger() {}

    /** How important a message is, from least to most. */
    public enum Level {
        /** Fine-grained detail for tracing a problem; hidden by default. */
        TRACE(0, "\u001B[37m"), // White
        /** Information useful while developing. */
        DEBUG(1, "\u001B[36m"), // Cyan
        /** Normal milestones: startup steps, loads, shutdown. */
        INFO(2,  "\u001B[32m"), // Green
        /** Something unexpected that the engine worked around. */
        WARN(3,  "\u001B[33m"), // Yellow
        /** Something failed. */
        ERROR(4, "\u001B[31m"); // Red

        final int priority;
        final String color;

        Level(int priority, String color) {
            this.priority = priority;
            this.color = color;
        }
    }

    /**
     * The subsystem a message comes from, shown as its label and filtered separately.
     * (Named {@code System}, so inside this class {@code java.lang.System} must be written
     * out in full.)
     */
    public enum System {
        /** Engine core: ECS, physics, files, startup and shutdown. */
        CORE("CORE"),
        /** The native window and GLFW. */
        WINDOW("WINDOW"),
        /** Rendering and the graphics backend. */
        RENDERER("RENDERER"),
        /** Shader compilation and uniforms. */
        SHADER("SHADER"),
        /** Asset loading, baking and hot reload. */
        ASSET("ASSET"),
        /** The editor interface. */
        UI("UI");

        final String label;
        Level currentLevel = Level.DEBUG; 

        System(String label) {
            this.label = label;
        }

        /**
         * Sets the least important level this subsystem prints. Affects every thread at once.
         *
         * @param level messages below this level are dropped
         */
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
            
            // Stack trace execution context parsing
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            String originInfo = "Unknown.java:?";
            
            // Index [0] is getStackTrace, [1] is log, [2] is wrapper shortcut. Index [3] is the true invocation site.
            if (stackTrace.length > 3) {
                StackTraceElement caller = stackTrace[3];
                originInfo = caller.getFileName() + ":" + caller.getLineNumber();
            }

            // Standard console output with ANSI color coding for terminal readability
            java.lang.System.out.printf("[%s] %s%-5s%s [%-8s] [%s] %s%n", 
                timestamp, lvl.color, lvl.name(), RESET, sys.label, originInfo, formattedMsg);
        }
    }

    /**
     * Logs at {@link Level#TRACE}.
     *
     * @param sys  the subsystem logging
     * @param msg  the message, with {@link String#format} placeholders
     * @param args values for the placeholders
     */
    public static void trace(System sys, String msg, Object... args) { log(sys, Level.TRACE, msg, args); }

    /**
     * Logs at {@link Level#DEBUG}.
     *
     * @param sys  the subsystem logging
     * @param msg  the message, with {@link String#format} placeholders
     * @param args values for the placeholders
     */
    public static void debug(System sys, String msg, Object... args) { log(sys, Level.DEBUG, msg, args); }

    /**
     * Logs at {@link Level#INFO}.
     *
     * @param sys  the subsystem logging
     * @param msg  the message, with {@link String#format} placeholders
     * @param args values for the placeholders
     */
    public static void info (System sys, String msg, Object... args) { log(sys, Level.INFO,  msg, args); }

    /**
     * Logs at {@link Level#WARN}.
     *
     * @param sys  the subsystem logging
     * @param msg  the message, with {@link String#format} placeholders
     * @param args values for the placeholders
     */
    public static void warn (System sys, String msg, Object... args) { log(sys, Level.WARN,  msg, args); }

    /**
     * Logs at {@link Level#ERROR}.
     *
     * @param sys  the subsystem logging
     * @param msg  the message, with {@link String#format} placeholders
     * @param args values for the placeholders
     */
    public static void error(System sys, String msg, Object... args) { log(sys, Level.ERROR, msg, args); }
}
