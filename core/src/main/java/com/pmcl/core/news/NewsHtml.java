package com.pmcl.core.news;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 Minecraft.net 文章正文拆成标题、段落、列表和图片。
 * 换行来自段落和 {@code <br>}，不会把整篇收成一行。
 */
public final class NewsHtml {

    public enum Kind { PARAGRAPH, HEADING, LIST_ITEM, IMAGE }

    public static final class Block {
        public final Kind kind;
        public final String text;
        public final int level;
        public final boolean ordered;
        public final int index;
        public final int depth;
        public final String url;
        public final String alt;

        Block(Kind kind, String text, int level, boolean ordered, int index, int depth, String url, String alt) {
            this.kind = kind;
            this.text = text == null ? "" : text;
            this.level = level;
            this.ordered = ordered;
            this.index = index;
            this.depth = depth;
            this.url = url == null ? "" : url;
            this.alt = alt == null ? "" : alt;
        }
    }

    private static final Pattern TOKEN = Pattern.compile(
            "<(h[1-6]|p|ul|ol|li|img|br|blockquote)\\b([^>]*?)/?>|</(h[1-6]|p|ul|ol|li|blockquote)\\s*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TAGS = Pattern.compile("<[^>]+>");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?[0-9a-fA-F]+);");
    private static final Pattern ATTR = Pattern.compile("\\b(src|alt)\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);

    private NewsHtml() {}

    public static List<Block> parse(String html) {
        if (html == null || html.isBlank()) return List.of();
        List<Block> blocks = new ArrayList<>();
        ArrayDeque<Buf> stack = new ArrayDeque<>();
        ArrayDeque<Boolean> lists = new ArrayDeque<>();
        ArrayDeque<Integer> counters = new ArrayDeque<>();
        Matcher matcher = TOKEN.matcher(html);
        int cursor = 0;
        while (matcher.find()) {
            appendText(stack, html.substring(cursor, matcher.start()));
            cursor = matcher.end();
            String open = matcher.group(1);
            String close = matcher.group(3);
            if (open != null) {
                String name = open.toLowerCase(Locale.ROOT);
                String attrs = matcher.group(2) == null ? "" : matcher.group(2);
                switch (name) {
                    case "br" -> appendLiteral(stack, "\n");
                    case "img" -> addImage(blocks, stack, attrs);
                    case "ul" -> openList(stack, blocks, lists, counters, false);
                    case "ol" -> openList(stack, blocks, lists, counters, true);
                    case "li" -> openItem(stack, lists, counters);
                    case "blockquote", "p" -> openText(stack, blocks, "p", 0);
                    default -> {
                        if (name.length() == 2 && name.charAt(0) == 'h') {
                            openText(stack, blocks, "h", name.charAt(1) - '0');
                        }
                    }
                }
            } else if (close != null) {
                String name = close.toLowerCase(Locale.ROOT);
                if ("ul".equals(name) || "ol".equals(name)) {
                    closeList(stack, blocks, lists, counters);
                } else if (name.startsWith("h")) {
                    closeKind(stack, blocks, "h");
                } else if ("li".equals(name)) {
                    closeKind(stack, blocks, "li");
                } else {
                    closeKind(stack, blocks, "p");
                }
            }
        }
        appendText(stack, html.substring(cursor));
        while (!stack.isEmpty()) emit(blocks, stack.removeLast());
        return blocks;
    }

    private static final class Buf {
        final String kind;
        final int level;
        final boolean ordered;
        final int index;
        final int depth;
        final StringBuilder text = new StringBuilder();

        Buf(String kind, int level, boolean ordered, int index, int depth) {
            this.kind = kind;
            this.level = level;
            this.ordered = ordered;
            this.index = index;
            this.depth = depth;
        }
    }

    private static void openList(ArrayDeque<Buf> stack, List<Block> blocks,
                                 ArrayDeque<Boolean> lists, ArrayDeque<Integer> counters, boolean ordered) {
        flushOpen(stack, blocks);
        lists.addLast(ordered);
        counters.addLast(0);
    }

    private static void closeList(ArrayDeque<Buf> stack, List<Block> blocks,
                                  ArrayDeque<Boolean> lists, ArrayDeque<Integer> counters) {
        if (!stack.isEmpty() && "li".equals(stack.peekLast().kind)) {
            emit(blocks, stack.removeLast());
        }
        if (!lists.isEmpty()) lists.removeLast();
        if (!counters.isEmpty()) counters.removeLast();
    }

    private static void openItem(ArrayDeque<Buf> stack, ArrayDeque<Boolean> lists, ArrayDeque<Integer> counters) {
        boolean ordered = !lists.isEmpty() && lists.peekLast();
        int index = 0;
        if (ordered && !counters.isEmpty()) {
            index = counters.removeLast() + 1;
            counters.addLast(index);
        }
        int depth = Math.max(0, lists.size() - 1);
        stack.addLast(new Buf("li", 0, ordered, index, depth));
    }

    private static void openText(ArrayDeque<Buf> stack, List<Block> blocks, String kind, int level) {
        flushOpen(stack, blocks);
        stack.addLast(new Buf(kind, level, false, 0, 0));
    }

    private static void closeKind(ArrayDeque<Buf> stack, List<Block> blocks, String kind) {
        while (!stack.isEmpty()) {
            Buf top = stack.removeLast();
            emit(blocks, top);
            if (kind.equals(top.kind)) return;
        }
    }

    private static void flushOpen(ArrayDeque<Buf> stack, List<Block> blocks) {
        Buf top = stack.peekLast();
        if (top == null || top.text.toString().isBlank()) return;
        Buf copy = new Buf(top.kind, top.level, top.ordered, top.index, top.depth);
        copy.text.append(top.text);
        top.text.setLength(0);
        emit(blocks, copy);
    }

    private static void addImage(List<Block> blocks, ArrayDeque<Buf> stack, String attrs) {
        flushOpen(stack, blocks);
        String src = attr(attrs, "src");
        if (src.isEmpty() || src.regionMatches(true, 0, "javascript:", 0, 11)) return;
        if (src.startsWith("/")) src = "https://www.minecraft.net" + src;
        if (!src.startsWith("https://") && !src.startsWith("http://")) return;
        blocks.add(new Block(Kind.IMAGE, "", 0, false, 0, 0, src, attr(attrs, "alt")));
    }

    private static void appendText(ArrayDeque<Buf> stack, String raw) {
        String text = inlineText(raw);
        if (text.isEmpty()) return;
        if (text.isBlank() && stack.isEmpty()) return;
        appendLiteral(stack, text);
    }

    private static void appendLiteral(ArrayDeque<Buf> stack, String text) {
        if (stack.isEmpty()) {
            if (text.isBlank()) return;
            stack.addLast(new Buf("p", 0, false, 0, 0));
        }
        stack.peekLast().text.append(text);
    }

    private static void emit(List<Block> blocks, Buf node) {
        String text = node.text.toString().replace('\u00a0', ' ');
        if ("h".equals(node.kind)) {
            text = text.replace('\n', ' ').replaceAll("[ \\t]{2,}", " ").trim();
            if (!text.isEmpty()) {
                blocks.add(new Block(Kind.HEADING, text, Math.max(1, node.level), false, 0, 0, "", ""));
            }
            return;
        }
        text = text.replaceAll("[ \\t]*\\n[ \\t]*", "\n").replaceAll("\\n{3,}", "\n\n").trim();
        if (text.isEmpty()) return;
        if ("li".equals(node.kind)) {
            blocks.add(new Block(Kind.LIST_ITEM, text.replace('\n', ' ').replaceAll(" {2,}", " ").trim(),
                    0, node.ordered, node.index, node.depth, "", ""));
            return;
        }
        for (String part : text.split("\\n\\n+")) {
            String paragraph = part.trim();
            if (!paragraph.isEmpty()) {
                blocks.add(new Block(Kind.PARAGRAPH, paragraph, 0, false, 0, 0, "", ""));
            }
        }
    }

    static String inlineText(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String text = TAGS.matcher(raw).replaceAll("");
        text = decodeEntities(text).replace('\u00a0', ' ');
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        text = text.replaceAll("[ \\t\\f]+", " ");
        text = text.replaceAll(" *\\n *", "\n");
        return text;
    }

    private static String decodeEntities(String value) {
        String text = value
                .replace("&nbsp;", " ")
                .replace("&#160;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'")
                .replace("&mdash;", "—")
                .replace("&ndash;", "–")
                .replace("&hellip;", "…")
                .replace("&rsquo;", "’")
                .replace("&lsquo;", "‘")
                .replace("&rdquo;", "”")
                .replace("&ldquo;", "“");
        Matcher matcher = NUMERIC_ENTITY.matcher(text);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String raw = matcher.group(1);
            int cp;
            try {
                cp = (raw.charAt(0) == 'x' || raw.charAt(0) == 'X')
                        ? Integer.parseInt(raw.substring(1), 16)
                        : Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                continue;
            }
            String repl = (cp >= 0 && cp <= 0x10FFFF) ? new String(Character.toChars(cp)) : matcher.group();
            matcher.appendReplacement(out, Matcher.quoteReplacement(repl));
        }
        matcher.appendTail(out);
        return out.toString().replace("&amp;", "&");
    }

    private static String attr(String tag, String name) {
        Matcher matcher = ATTR.matcher(tag);
        while (matcher.find()) {
            if (name.equalsIgnoreCase(matcher.group(1))) return matcher.group(2).trim();
        }
        return "";
    }
}
