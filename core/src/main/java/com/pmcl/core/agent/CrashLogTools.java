package com.pmcl.core.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * 崩溃日志只在内存快照里读。file_id 对不上就拒绝，不接受文件路径。
 * 行数、字数和搜索范围与 Axolotl LogAgent 的诊断工具相同。
 */
public final class CrashLogTools {

    private static final int READ_SPAN = 160;
    private static final int LINE_CHARS = 2000;
    private static final int WINDOW_CHARS = 12_000;
    private static final int GREP_MATCHES = 30;
    private static final int MAX_LINES = 2500;

    private CrashLogTools() {}

    public static final class LogFile {
        public final String id;
        public final String name;
        public final String sourceType;
        public final int firstLine;
        public final int lineCount;
        public final boolean truncated;
        final List<String> lines;

        LogFile(String id, String name, String sourceType, int firstLine, boolean truncated, List<String> lines) {
            this.id = id;
            this.name = name;
            this.sourceType = sourceType;
            this.firstLine = firstLine;
            this.lineCount = lines.size();
            this.truncated = truncated;
            this.lines = lines;
        }
    }

    /** 弹窗里已有的崩溃报告和最近日志。行号从 1 开始，超长的前段会被丢掉并标 truncated。 */
    public static List<LogFile> snapshot(String report, String log) {
        List<LogFile> files = new ArrayList<>();
        if (report != null && !report.isBlank()) {
            files.add(file("log-1", "crash-report.txt", "crash_report", report));
        }
        if (log != null && !log.isBlank()) {
            files.add(file(files.isEmpty() ? "log-1" : "log-2", "latest.log", "minecraft_log", log));
        }
        return files;
    }

    public static JsonObject execute(List<LogFile> files, JsonObject request) {
        String name = text(request, "name");
        if ("list_log_files".equals(name)) {
            JsonObject wrap = new JsonObject();
            wrap.add("files", listFiles(files));
            return wrap;
        }
        LogFile file = find(files, text(request, "file_id"));
        if (file == null) {
            return error("Unknown file_id. Use list_log_files; filesystem paths are not accepted.");
        }
        boolean truncated;
        TreeSet<Integer> selected = new TreeSet<>();
        if ("read_log_file".equals(name)) {
            int start = Math.max(number(request, "start_line", file.firstLine), file.firstLine);
            int requestedEnd = number(request, "end_line", start + 79);
            int end = Math.min(requestedEnd, start + READ_SPAN - 1);
            truncated = requestedEnd > end;
            for (int line = start; line <= end && selected.size() < READ_SPAN; line++) {
                int index = line - file.firstLine;
                if (index >= 0 && index < file.lines.size()) selected.add(index);
            }
        } else if ("grep_log_file".equals(name)) {
            String query = text(request, "query");
            if (query.isEmpty() || query.length() > 1024) {
                return error("Supply a nonempty literal query of at most 256 characters.");
            }
            String needle = query.toLowerCase(Locale.ROOT);
            int context = Math.min(Math.max(number(request, "context", 3), 0), 10);
            List<Integer> hits = new ArrayList<>();
            for (int i = 0; i < file.lines.size(); i++) {
                if (file.lines.get(i).toLowerCase(Locale.ROOT).contains(needle)) hits.add(i);
            }
            truncated = hits.size() > GREP_MATCHES;
            int keep = Math.min(hits.size(), GREP_MATCHES);
            for (int n = 0; n < keep; n++) {
                int index = hits.get(n);
                int from = Math.max(0, index - context);
                int to = Math.min(file.lines.size() - 1, index + context);
                for (int i = from; i <= to; i++) selected.add(i);
            }
        } else {
            return error("Unsupported diagnostic tool");
        }
        return window(file, selected, truncated);
    }

    /** 先给崩溃报告里异常附近的一段，其余留给工具再翻。 */
    public static String initialWindow(List<LogFile> files) {
        List<LogFile> ordered = new ArrayList<>(files);
        ordered.sort((a, b) -> Integer.compare(rank(a.sourceType), rank(b.sourceType)));
        StringBuilder out = new StringBuilder();
        int budget = WINDOW_CHARS;
        for (LogFile file : ordered) {
            if (budget == 0) break;
            int focus = 0;
            boolean found = false;
            for (int i = 0; i < file.lines.size(); i++) {
                String line = file.lines.get(i).toLowerCase(Locale.ROOT);
                if (line.contains("caused by:") || line.contains("exception") || line.contains("fatal")
                        || line.contains("error") || line.contains("problematic frame")) {
                    focus = i;
                    found = true;
                    break;
                }
            }
            if (!found) focus = Math.max(0, file.lines.size() - 50);
            int start = file.firstLine + Math.max(0, focus - 12);
            JsonObject request = new JsonObject();
            request.addProperty("name", "read_log_file");
            request.addProperty("file_id", file.id);
            request.addProperty("start_line", start);
            request.addProperty("end_line", start + 79);
            String text = execute(List.of(file), request).toString();
            if (text.length() > budget) break;
            budget -= text.length();
            if (out.length() > 0) out.append('\n');
            out.append(text);
        }
        return out.toString();
    }

    static String sanitize(String text) {
        if (text == null || text.isEmpty()) return "";
        String out = text;
        String home = System.getProperty("user.home", "");
        if (home.length() >= 3) out = out.replace(home, "{HOME}");
        String user = System.getProperty("user.name", "");
        if (user.length() >= 3) out = out.replace(user, "{COMPUTER_USERNAME}");
        return out;
    }

    private static LogFile file(String id, String name, String sourceType, String content) {
        String[] raw = content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<String> lines = new ArrayList<>();
        boolean truncated = raw.length > MAX_LINES;
        int limit = Math.min(raw.length, MAX_LINES);
        for (int i = 0; i < limit; i++) {
            String line = sanitize(raw[i]);
            if (line.codePointCount(0, line.length()) > 4000) {
                line = line.substring(0, line.offsetByCodePoints(0, 4000));
                truncated = true;
            }
            lines.add(line);
        }
        return new LogFile(id, name, sourceType, 1, truncated, lines);
    }

    private static JsonObject window(LogFile file, TreeSet<Integer> selected, boolean truncated) {
        int remaining = WINDOW_CHARS;
        JsonArray lines = new JsonArray();
        for (int index : selected) {
            String text = file.lines.get(index);
            int room = Math.min(remaining, LINE_CHARS);
            String excerpt = take(text, room);
            remaining -= excerpt.codePointCount(0, excerpt.length());
            if (excerpt.length() < text.length()) truncated = true;
            JsonObject row = new JsonObject();
            row.addProperty("line", file.firstLine + index);
            row.addProperty("text", excerpt);
            lines.add(row);
            if (remaining == 0 || lines.size() >= READ_SPAN) {
                truncated = true;
                break;
            }
        }
        JsonObject out = new JsonObject();
        out.addProperty("file_id", file.id);
        out.addProperty("name", file.name);
        out.addProperty("first_line", file.firstLine);
        out.add("lines", lines);
        out.addProperty("truncated", truncated);
        return out;
    }

    private static JsonArray listFiles(List<LogFile> files) {
        JsonArray array = new JsonArray();
        if (files == null) return array;
        for (LogFile file : files) {
            JsonObject row = new JsonObject();
            row.addProperty("id", file.id);
            row.addProperty("name", file.name);
            row.addProperty("source_type", file.sourceType);
            row.addProperty("first_line", file.firstLine);
            row.addProperty("line_count", file.lineCount);
            row.addProperty("truncated", file.truncated);
            array.add(row);
        }
        return array;
    }

    private static LogFile find(List<LogFile> files, String id) {
        if (files == null || id == null || id.isBlank()) return null;
        for (LogFile file : files) {
            if (file.id.equals(id)) return file;
        }
        return null;
    }

    private static JsonObject error(String message) {
        JsonObject out = new JsonObject();
        out.addProperty("error", message);
        return out;
    }

    private static String text(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) return "";
        return object.get(key).getAsString();
    }

    private static int number(JsonObject object, String key, int fallback) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) return fallback;
        try {
            long value = object.get(key).getAsLong();
            if (value < 0 || value > Integer.MAX_VALUE - 200) return Integer.MAX_VALUE - 200;
            return (int) value;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static int rank(String sourceType) {
        if ("crash_report".equals(sourceType)) return 0;
        if ("jvm_crash".equals(sourceType)) return 1;
        return 2;
    }

    private static String take(String text, int codePoints) {
        if (text == null || codePoints <= 0) return "";
        if (text.codePointCount(0, text.length()) <= codePoints) return text;
        return text.substring(0, text.offsetByCodePoints(0, codePoints));
    }
}
