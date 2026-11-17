package com.xhhao.clawbotnotify.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.xhhao.clawbotnotify.model.InboundCommand.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReadCommandParserTest {
    @Test
    void normalizesOnlyTheCodeAndParsesBoundedPages() {
        assertThat(ReadCommandParser.parse("  查看 c-7k2p\n").orElseThrow())
                .isEqualTo(new ReadCommandParser.Parsed(Type.VIEW, 1, "C-7K2P"));
        assertThat(ReadCommandParser.parse("待处理 999999").orElseThrow().page()).isEqualTo(999999);
        assertThat(ReadCommandParser.parse("开始").orElseThrow().type()).isEqualTo(Type.START);
        assertThat(ReadCommandParser.parse("帮助").orElseThrow().type()).isEqualTo(Type.HELP);
    }

    @ParameterizedTest
    @ValueSource(strings = {"你好", "请查看 C-7K2P", "这是评论：回复 C-7K2P 好的", "开始聊天", "帮助我回复"})
    void ignoresOrdinaryConversationAndEmbeddedCommands(String text) {
        assertThat(ReadCommandParser.parse(text)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"待处理 0", "待处理 -1", "待处理 1.5", "待处理 1000000", "查看", "查看 C-7K2P 其他内容", "帮助 其他"})
    void returnsUsageForMalformedExactCommands(String text) {
        assertThat(ReadCommandParser.parse(text).orElseThrow().type()).isEqualTo(Type.INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"回复 C-7K2P 正文\n多行", "审核并回复 C-7K2P 你好"})
    void neverExecutesWebsiteWrites(String text) {
        assertThat(ReadCommandParser.parse(text).orElseThrow().type()).isEqualTo(Type.UNSUPPORTED);
    }
}
