package com.hippo.ehviewer.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MarkdownParser {

    private static final Pattern HEADER = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern ITALIC = Pattern.compile("\\*(.+?)\\*");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)]+)\\)");
    private static final Pattern CODE_BLOCK = Pattern.compile("```(\\w*)\\n([\\s\\S]*?)```", Pattern.MULTILINE);
    private static final Pattern BLOCKQUOTE = Pattern.compile("^>\\s?(.+)$", Pattern.MULTILINE);
    private static final Pattern HORIZONTAL_RULE = Pattern.compile("^[-*_]{3,}\\s*$", Pattern.MULTILINE);
    private static final Pattern UNORDERED_LIST = Pattern.compile("^[-*+]\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern ORDERED_LIST = Pattern.compile("^\\d+\\.\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern TABLE_LINE = Pattern.compile("^\\|(.+)\\|$", Pattern.MULTILINE);

    public static String toHtml(String markdown) {
        return toHtml(markdown, true);
    }

    public static String toHtml(String markdown, boolean isDarkMode) {
        String textColor = isDarkMode ? "#E0E0E0" : "#212121";
        String secondaryTextColor = isDarkMode ? "#AAAAAA" : "#616161";
        String backgroundColor = isDarkMode ? "#121212" : "#FFFFFF";
        String borderColor = isDarkMode ? "#444444" : "#DDDDDD";
        String codeBg = isDarkMode ? "#2A2A2A" : "#F0F0F0";
        String tableHeaderBg = isDarkMode ? "#333333" : "#EEEEEE";
        String linkColor = isDarkMode ? "#82B1FF" : "#1976D2";

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head>");
        html.append("<meta charset=\"utf-8\">");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
        html.append("<style>");
        html.append("body { font-family: -apple-system, 'Segoe UI', Roboto, sans-serif; ");
        html.append("font-size: 15px; line-height: 1.7; padding: 16px; margin: 0; ");
        html.append("background-color: ").append(backgroundColor).append("; ");
        html.append("color: ").append(textColor).append("; ");
        html.append("word-wrap: break-word; overflow-wrap: break-word; }");
        html.append("h1 { font-size: 1.6em; border-bottom: 1px solid ").append(borderColor).append("; padding-bottom: 8px; margin: 24px 0 16px; }");
        html.append("h2 { font-size: 1.3em; border-bottom: 1px solid ").append(borderColor).append("; padding-bottom: 6px; margin: 20px 0 12px; }");
        html.append("h3 { font-size: 1.1em; margin: 16px 0 10px; }");
        html.append("p { margin: 8px 0; }");
        html.append("ul, ol { padding-left: 24px; margin: 8px 0; }");
        html.append("li { margin: 4px 0; }");
        html.append("code { background: ").append(codeBg).append("; padding: 2px 6px; border-radius: 4px; font-family: monospace; font-size: 0.9em; }");
        html.append("pre { background: ").append(codeBg).append("; padding: 12px; border-radius: 6px; overflow-x: auto; }");
        html.append("pre code { background: none; padding: 0; border-radius: 0; }");
        html.append("blockquote { border-left: 3px solid ").append(borderColor).append("; padding: 4px 16px; margin: 8px 0; color: ").append(secondaryTextColor).append("; }");
        html.append("table { border-collapse: collapse; width: 100%; margin: 12px 0; table-layout: fixed; }");
        html.append("th, td { border: 1px solid ").append(borderColor).append("; padding: 8px; text-align: left; word-wrap: break-word; }");
        html.append("th { background: ").append(tableHeaderBg).append("; font-weight: 600; }");
        html.append("img { max-width: 100%; height: auto; }");
        html.append("hr { border: none; border-top: 1px solid ").append(borderColor).append("; margin: 16px 0; }");
        html.append("a { color: ").append(linkColor).append("; }");
        html.append("strong { font-weight: 600; }");
        html.append("</style></head><body>");

        String body = markdown;

        // Step 0: Escape HTML entities
        body = body.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");

        // Step 1: Code blocks (before other processing to avoid inline parsing)
        StringBuffer sb = new StringBuffer();
        Matcher m = CODE_BLOCK.matcher(body);
        while (m.find()) {
            String lang = m.group(1);
            String code = m.group(2).trim();
            m.appendReplacement(sb, Matcher.quoteReplacement("<pre><code>" + code + "</code></pre>"));
        }
        m.appendTail(sb);
        body = sb.toString();

        // Step 2: Process block elements line by line
        String[] lines = body.split("\n");
        sb = new StringBuffer();
        boolean inList = false;
        boolean inOrderedList = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                if (inList) { sb.append("</ul>\n"); inList = false; }
                if (inOrderedList) { sb.append("</ol>\n"); inOrderedList = false; }
                continue;
            }

            // Headers
            Matcher hm = HEADER.matcher(line);
            if (hm.matches()) {
                if (inList) { sb.append("</ul>\n"); inList = false; }
                if (inOrderedList) { sb.append("</ol>\n"); inOrderedList = false; }
                int level = hm.group(1).length();
                sb.append("<h").append(level).append(">").append(parseInline(hm.group(2))).append("</h").append(level).append(">\n");
                continue;
            }

            // Horizontal rule
            if (HORIZONTAL_RULE.matcher(trimmed).matches()) {
                if (inList) { sb.append("</ul>\n"); inList = false; }
                if (inOrderedList) { sb.append("</ol>\n"); inOrderedList = false; }
                sb.append("<hr>\n");
                continue;
            }

            // Blockquote
            Matcher bqm = BLOCKQUOTE.matcher(line);
            if (bqm.matches()) {
                if (inList) { sb.append("</ul>\n"); inList = false; }
                if (inOrderedList) { sb.append("</ol>\n"); inOrderedList = false; }
                sb.append("<blockquote>").append(parseInline(bqm.group(1))).append("</blockquote>\n");
                continue;
            }

            // Unordered list
            Matcher ulm = UNORDERED_LIST.matcher(line);
            if (ulm.matches()) {
                if (inOrderedList) { sb.append("</ol>\n"); inOrderedList = false; }
                if (!inList) { sb.append("<ul>\n"); inList = true; }
                sb.append("<li>").append(parseInline(ulm.group(1))).append("</li>\n");
                continue;
            }

            // Ordered list
            Matcher olm = ORDERED_LIST.matcher(line);
            if (olm.matches()) {
                if (inList) { sb.append("</ul>\n"); inList = false; }
                if (!inOrderedList) { sb.append("<ol>\n"); inOrderedList = true; }
                sb.append("<li>").append(parseInline(olm.group(1))).append("</li>\n");
                continue;
            }

            // Table
            Matcher tlm = TABLE_LINE.matcher(trimmed);
            if (tlm.matches()) {
                if (inList) { sb.append("</ul>\n"); inList = false; }
                if (inOrderedList) { sb.append("</ol>\n"); inOrderedList = false; }
                String[] cells = trimmed.substring(1, trimmed.length() - 1).split("\\|");
                boolean isHeader = (i + 1 < lines.length && lines[i + 1].trim().matches("^\\|[\\s\\-:]+\\|$"));
                if (!isHeader && (i == 0 || !TABLE_LINE.matcher(lines[i - 1].trim()).matches())) {
                    // Start of new table
                }
                String tag = isHeader ? "th" : "td";
                sb.append("<tr>");
                for (String cell : cells) {
                    sb.append("<").append(tag).append(">").append(parseInline(cell.trim())).append("</").append(tag).append(">");
                }
                sb.append("</tr>\n");
                if (isHeader) {
                    i++; // skip separator line
                }
                // Simple table handling: wrap in <table> around adjacent rows
                continue;
            }

            // Regular paragraph
            if (inList) { sb.append("</ul>\n"); inList = false; }
            if (inOrderedList) { sb.append("</ol>\n"); inOrderedList = false; }
            sb.append("<p>").append(parseInline(trimmed)).append("</p>\n");
        }

        if (inList) { sb.append("</ul>\n"); }
        if (inOrderedList) { sb.append("</ol>\n"); }

        body = sb.toString();

        // Step 3: Wrap adjacent <tr> elements in <table>
        body = body.replaceAll("(?:<tr>.+?</tr>\\s*)+", "<table>$0</table>");

        html.append(body);
        html.append("</body></html>");
        return html.toString();
    }

    private static String parseInline(String text) {
        // Bold
        text = BOLD.matcher(text).replaceAll("<strong>$1</strong>");
        // Italic (after bold to avoid conflict with **)
        text = ITALIC.matcher(text).replaceAll("<em>$1</em>");
        // Inline code
        text = INLINE_CODE.matcher(text).replaceAll("<code>$1</code>");
        // Images (before links to avoid conflict)
        text = IMAGE.matcher(text).replaceAll("<img src=\"$2\" alt=\"$1\">");
        // Links
        text = LINK.matcher(text).replaceAll("<a href=\"$2\">$1</a>");
        return text;
    }
}
