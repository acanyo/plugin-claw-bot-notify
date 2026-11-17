package com.xhhao.clawbotnotify.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhhao.clawbotnotify.model.OutboundTask;
import com.xhhao.clawbotnotify.model.WechatBinding;
import com.xhhao.clawbotnotify.support.ExtensionFixture;
import org.junit.jupiter.api.Test;
import run.halo.app.extension.Metadata;

class QuotedCommentResolverTest {
    final ExtensionFixture fixture = new ExtensionFixture();
    final ObjectMapper json = new ObjectMapper();
    final WechatBinding binding = new WechatBinding();
    final QuotedCommentResolver resolver = new QuotedCommentResolver(fixture.client);

    QuotedCommentResolverTest() {
        binding.setSpec(new WechatBinding.Spec());
        binding.getSpec().setGeneration(3);
        binding.getSpec().setOwnerWechatUserId("owner");
    }

    OutboundTask outbound(String name, String target, String body, String id) {
        var task = new OutboundTask();
        var meta = new Metadata();
        meta.setName(name);
        task.setMetadata(meta);
        var spec = new OutboundTask.Spec();
        task.setSpec(spec);
        spec.setGeneration(3);
        spec.setTargetUserId("owner");
        spec.setCommentName(target);
        spec.setState(OutboundTask.State.ACCEPTED);
        spec.setUpstreamMessageId(id);
        spec.setQuoteTextHash(QuotedCommentResolver.fingerprint(body));
        spec.setCommandName("draft-" + name);
        return task;
    }

    ObjectNode quote(String id, String text) {
        var message = json.createObjectNode();
        var ref = message.putArray("item_list").addObject().putObject("ref_msg");
        if (id != null) ref.put("svr_id", id);
        if (text != null) ref.putObject("message_item").putObject("text_item").put("text", text);
        return message;
    }

    @Test
    void resolvesUint64IdAndExactTextWhenAcknowledgementOmitsId() {
        fixture.put(outbound("one", "comment-a", "**新评论**\n正文", "18446744073709551615"), true);
        assertThat(resolver.resolve(binding, quote("18446744073709551615", null))
                        .block()
                        .commentName())
                .isEqualTo("comment-a");
        assertThat(resolver.resolve(binding, quote("new-id", " **新评论**\r\n正文 "))
                        .block()
                        .commandName())
                .isEqualTo("draft-one");
    }

    @Test
    void refusesForeignOwnerGenerationAndUnsentTasks() {
        for (int i = 0; i < 3; i++) {
            var task = outbound("bad-" + i, "comment-a", "text-" + i, "id-" + i);
            if (i == 0) task.getSpec().setTargetUserId("foreign");
            if (i == 1) task.getSpec().setGeneration(2);
            if (i == 2) task.getSpec().setState(OutboundTask.State.PENDING);
            fixture.put(task, true);
            assertThat(resolver.resolve(binding, quote("id-" + i, "text-" + i)).block())
                    .isNull();
        }
    }

    @Test
    void numbersPartialTextAndListsCannotSelectAComment() {
        fixture.put(outbound("comment", "comment-a", "完整评论 C-ABC123 正文", null), true);
        fixture.put(outbound("list", null, "待处理 C-ABC123", "list-id"), true);
        assertThat(resolver.resolve(binding, quote(null, "C-ABC123")).block()).isNull();
        assertThat(resolver.resolve(binding, quote(null, "完整评论 C-ABC123")).block())
                .isNull();
        assertThat(resolver.resolve(binding, quote("list-id", "待处理 C-ABC123")).block())
                .isNull();
    }

    @Test
    void ambiguousIdenticalTextCannotSelectBetweenComments() {
        fixture.put(outbound("a", "comment-a", "相同正文", null), true);
        fixture.put(outbound("b", "comment-b", "相同正文", null), true);
        assertThat(resolver.resolve(binding, quote(null, "相同正文")).block()).isNull();
    }

    @Test
    void conflictingReferencesAreRejectedAndSameCommentDuplicatesCannotSelectADraft() {
        fixture.put(outbound("a", "comment-a", "相同正文", "a"), true);
        fixture.put(outbound("b", "comment-a", "相同正文", "b"), true);
        assertThat(resolver.resolve(binding, quote(null, "相同正文")).block().commandName())
                .isNull();
        var message = quote("a", null);
        message.withArray("item_list").addObject().putObject("ref_msg").put("svr_id", "b");
        assertThat(resolver.resolve(binding, message).block()).isNull();
    }
}
