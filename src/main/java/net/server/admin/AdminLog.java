package net.server.admin;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The server's console as the admin page shows it: the last lines of its log (everything the root logger writes at
 * info and above) and of what is printed straight to the standard output and error, kept in memory.
 */
public final class AdminLog extends AbstractAppender {
    private static final int KEEP = 5000;
    private static final Pattern ERROR = Pattern.compile("\\bERROR\\b|Exception|^\\s+at ");
    private static final Pattern WARN = Pattern.compile("\\bWARN\\b");
    private static final Deque<Map<String, Object>> lines = new ArrayDeque<>();
    private static long count = 0;
    private static boolean installed = false;

    private AdminLog(PatternLayout layout) {
        super("AdminConsole", null, layout, true, Property.EMPTY_ARRAY);
    }

    /** Starts keeping the console. Called once, as early as the server starts. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Configuration config = context.getConfiguration();
        PatternLayout layout = PatternLayout.newBuilder().withConfiguration(config).withPattern("%-5level %c{1} - %msg%n%throwable").build();
        AdminLog appender = new AdminLog(layout);
        appender.start();
        config.addAppender(appender);
        config.getRootLogger().addAppender(appender, Level.INFO, null);
        context.updateLoggers();
        System.setOut(new PrintStream(new Tee(System.out, false), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new Tee(System.err, true), true, StandardCharsets.UTF_8));
    }

    @Override
    public void append(LogEvent event) {
        String level = event.getLevel().isMoreSpecificThan(Level.ERROR) ? "error" : event.getLevel() == Level.WARN ? "warn" : "log";
        String text = getLayout().toSerializable(event).toString();
        for (String line : text.split("\n")) {
            if (!line.isBlank()) {
                add(level, line.stripTrailing());
            }
        }
    }

    private static synchronized void add(String level, String text) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("n", ++count);
        line.put("t", System.currentTimeMillis());
        line.put("level", level);
        line.put("text", text.length() > 4000 ? text.substring(0, 4000) : text);
        lines.addLast(line);
        while (lines.size() > KEEP) {
            lines.removeFirst();
        }
    }

    /** The lines after line number `after`. */
    static synchronized List<Map<String, Object>> after(long after) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> line : lines) {
            if ((Long) line.get("n") > after) {
                out.add(line);
            }
        }
        return out;
    }

    /** What is printed goes on as before, and each line of it into the console as well. */
    private static final class Tee extends OutputStream {
        private final PrintStream out;
        private final boolean err;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        Tee(PrintStream out, boolean err) {
            this.out = out;
            this.err = err;
        }

        @Override
        public synchronized void write(int b) {
            out.write(b);
            if (b == '\n') {
                flushLine();
            } else if (line.size() < 8192) {
                line.write(b);
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            out.write(b, off, len);
            for (int k = off; k < off + len; k++) {
                if (b[k] == '\n') {
                    flushLine();
                } else if (line.size() < 8192) {
                    line.write(b[k]);
                }
            }
        }

        @Override
        public void flush() {
            out.flush();
        }

        private void flushLine() {
            String text = line.toString(StandardCharsets.UTF_8).stripTrailing();
            line.reset();
            if (text.isBlank()) {
                return;
            }
            String level = ERROR.matcher(text).find() ? "error" : WARN.matcher(text).find() ? "warn" : err ? "warn" : "log";
            add(level, text);
        }
    }
}
