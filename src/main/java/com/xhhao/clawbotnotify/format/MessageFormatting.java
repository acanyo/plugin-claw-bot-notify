package com.xhhao.clawbotnotify.format;

import java.net.URI;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Safelist;

/** Restricted Markdown for WeChat and safe rendered HTML for Halo. No content is fetched. */
public final class MessageFormatting {
    private static final Parser PARSER = Parser.builder().build();
    private static final HtmlRenderer HTML = HtmlRenderer.builder()
            .escapeHtml(true)
            .sanitizeUrls(true)
            .softbreak("<br />\n")
            .build();

    private MessageFormatting() {}

    public static String replyHtml(String markdown) {
        var safe = Safelist.basic()
                // Halo 2.27 rejects rel attributes; Jsoup basic adds rel=nofollow by default.
                .removeEnforcedAttribute("a", "rel")
                .addTags("p", "pre", "code", "h1", "h2", "h3", "h4")
                .removeTags("img")
                .addProtocols("a", "href", "http", "https");
        // Halo's relaxed HTML contract also excludes hr; retain its meaning as a text separator.
        String rendered = HTML.render(PARSER.parse(markdown)).replace("<hr />", "<p>—</p>");
        return Jsoup.clean(rendered, safe);
    }

    public static String commentMarkdown(String html, String raw, int limit) {
        if (html == null || html.isBlank()) return truncate(raw == null ? "" : raw, limit);
        var document = Jsoup.parseBodyFragment(html);
        document.select("script,style,iframe,object,svg").remove();
        // Truncate text nodes before adding Markdown so formatting delimiters stay balanced.
        int[] remaining = {limit};
        boolean[] truncated = {false};
        trim(document.body(), remaining, truncated);
        String rendered = render(document.body()).replaceAll("\\n{3,}", "\n\n").strip();
        return rendered + (truncated[0] ? "…" : "");
    }

    private static void trim(Node node, int[] remaining, boolean[] truncated) {
        if (node instanceof TextNode text) {
            String value = text.getWholeText();
            int count = value.codePointCount(0, value.length());
            if (count > remaining[0]) {
                text.text(value.substring(0, value.offsetByCodePoints(0, remaining[0])));
                remaining[0] = 0;
                truncated[0] = true;
            } else remaining[0] -= count;
        } else for (Node child : node.childNodes()) trim(child, remaining, truncated);
    }

    private static String render(Node node) {
        if (node instanceof TextNode text) return text.getWholeText();
        if (!(node instanceof Element element)) return "";
        StringBuilder children = new StringBuilder();
        for (Node child : element.childNodes()) children.append(render(child));
        String value = children.toString();
        if (value.isBlank()
                && !element.tagName().equals("br")
                && !element.tagName().equals("hr")) return "";
        return switch (element.tagName()) {
            case "strong", "b" -> "**" + value.strip() + "**";
            case "em", "i" -> value; // CJK italics are filtered by the official client as well.
            case "code" ->
                element.parent() != null && element.parent().tagName().equals("pre")
                        ? value
                        : "`" + value.replace('`', 'ˋ') + "`";
            case "pre" -> "\n\n```\n" + value.replace("```", "ˋˋˋ") + "\n```\n\n";
            case "a" ->
                safeUrl(element.attr("href"))
                        ? "[" + value + "]("
                                + element.attr("href").replace("(", "%28").replace(")", "%29") + ")"
                        : value;
            case "br" -> "\n";
            case "li" -> "• " + value.strip() + "\n";
            case "p", "div", "ul", "ol", "blockquote", "h1", "h2", "h3", "h4", "h5", "h6" ->
                "\n\n" + value.strip() + "\n\n";
            case "hr" -> "\n---\n";
            default -> value;
        };
    }

    private static boolean safeUrl(String value) {
        try {
            return java.util.Set.of("http", "https").contains(URI.create(value).getScheme());
        } catch (Exception e) {
            return false;
        }
    }

    public static String truncate(String text, int limit) {
        String safe = text.replaceAll("[\\p{Cc}&&[^\\n\\t]]", "").strip();
        return safe.codePointCount(0, safe.length()) <= limit
                ? safe
                : safe.substring(0, safe.offsetByCodePoints(0, limit)) + "…";
    }
}
