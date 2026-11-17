package com.xhhao.clawbotnotify.format;

import static org.assertj.core.api.Assertions.assertThat;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MessageFormattingTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "[Halo](https://www.halo.run)",
                "https://www.halo.run",
                "# 标题\n\n**内容**",
                "正文\n\n---\n\n另一段",
                "```java\nprintln(1);\n```",
                "<script>alert(1)</script>\n\n[坏链接](javascript:bad())"
            })
    void renderedReplySatisfiesHalo227CommentHtmlContract(String markdown) {
        // Target: halo v2.27.0 AbstractCommentService.isSafeHtml, not our own output allowlist.
        var haloAllowed = Safelist.relaxed()
                .addTags("s")
                .addAttributes("code", "class")
                .addAttributes("a", "target")
                .preserveRelativeLinks(true);
        String html = MessageFormatting.replyHtml(markdown);
        assertThat(Jsoup.isValid(html, haloAllowed))
                .as("Halo must accept generated reply HTML")
                .isTrue();
        assertThat(html).doesNotContain("rel=");
    }

    @Test
    void htmlCommentKeepsParagraphsBoldLinksListsAndCode() {
        String result = MessageFormatting.commentMarkdown(
                "<p><strong>重要</strong> 第一段</p><p>第二段 <a href='https://example.com'>参考</a></p><ul><li>步骤一</li><li>步骤二</li></ul><pre><code>x &lt; 3</code></pre>",
                "",
                300);
        assertThat(result).contains("**重要**", "\n\n第二段", "[参考](https://example.com)", "• 步骤一", "```\nx < 3\n```");
        assertThat(MessageFormatting.commentMarkdown("<p><b>😀😀😀😀</b></p>", "", 3))
                .isEqualTo("**😀😀😀**…");
    }

    @Test
    void markdownReplyRendersSafeHtmlAndTreatsRawHtmlAsLiteralText() {
        String result = MessageFormatting.replyHtml(
                "**支持**\n下一行\n\n- 一\n- 二\n\n`code`\n\n<script>alert(1)</script>\n\n[危险](javascript:alert(1))");
        assertThat(result)
                .contains("<strong>支持</strong>", "<br", "<ul>", "<code>code</code>", "&lt;script&gt;")
                .doesNotContain("<script>", "href=\"javascript:", "onerror=");
    }

    @Test
    void incomingCommentScriptsAndUnsupportedMediaAreNeverEmittedAsMarkup() {
        assertThat(MessageFormatting.commentMarkdown(
                        "<p>评论</p><script>secret()</script><style>bad{}</style><img src='javascript:x' onerror='bad()'><a href='javascript:bad()'>链接</a>",
                        "",
                        100))
                .contains("评论", "链接")
                .doesNotContain("secret", "bad", "javascript", "onerror", "<img");
    }
}
