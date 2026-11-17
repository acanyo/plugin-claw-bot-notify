package com.xhhao.clawbotnotify.assistant.review;

import com.xhhao.clawbotnotify.assistant.AssistantAuthority;
import com.xhhao.clawbotnotify.assistant.cli.AssistantTools;
import com.xhhao.clawbotnotify.assistant.draft.AssistantDrafts;
import com.xhhao.clawbotnotify.credential.CredentialStore;
import com.xhhao.clawbotnotify.model.InboundCommand;
import com.xhhao.clawbotnotify.model.WechatBinding;
import java.util.List;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;

/** Preview navigation never claims a proposal, invokes a model, or writes site content. */
@Component
public class AssistantPreviewReader {
    private final ReactiveExtensionClient resources;
    private final CredentialStore secrets;
    private final AssistantDrafts drafts;
    private final AssistantAuthority authority;

    public AssistantPreviewReader(
            ReactiveExtensionClient resources,
            CredentialStore secrets,
            AssistantDrafts drafts,
            AssistantAuthority authority) {
        this.resources = resources;
        this.secrets = secrets;
        this.drafts = drafts;
        this.authority = authority;
    }

    public Mono<InboundCommand> process(WechatBinding binding, InboundCommand command) {
        return secrets.read(command.getSpec().getBodyRef()).flatMap(data -> {
            if (data.containsKey("aiResponse")) return finish(command, data.get("aiError"));
            return authority
                    .check(binding)
                    .then(drafts.load(binding, command.getSpec().getPreviewOf()))
                    .flatMap(draft -> resources
                            .get(InboundCommand.class, command.getSpec().getPreviewOf())
                            .flatMap(source -> secrets.read(source.getSpec().getBodyRef()))
                            .map(source -> {
                                var before = source.containsKey("proposalBefore")
                                        ? drafts.input(source.get("proposalBefore"))
                                        : java.util.Map.<String, Object>of();
                                var view = DraftReview.view(draft, before);
                                List<String> parts =
                                        command.getSpec().getPage() <= 0 ? DraftReview.parts(diff(view)) : view.parts();
                                int page =
                                        Math.max(1, Math.abs(command.getSpec().getPage()));
                                if (page > parts.size()) throw new IllegalStateException("AI_PREVIEW_PAGE_INVALID");
                                return new Result(
                                        "**" + (command.getSpec().getPage() <= 0 ? "修改差异" : "全文预览") + " · 版本 "
                                                + view.version()
                                                + " · " + page + "/" + parts.size() + "**\n" + view.title() + "\n\n"
                                                + parts.get(page - 1)
                                                + "\n\n全文 " + view.characters()
                                                + " 字。引用预览发送“查看第 N 段”或“查看差异第 N 段”。完整差异也可在后台指令详情查看。确认此版本请引用发送“"
                                                + view.confirmation()
                                                + "”。",
                                        null);
                            }))
                    .onErrorResume(error -> Mono.just(new Result(
                            "无法读取预览（" + AssistantTools.safeCode(error) + "），请引用仍有效的最新预览。",
                            AssistantTools.safeCode(error))))
                    .flatMap(result -> secrets.update(command.getSpec().getBodyRef(), values -> {
                                values.put("aiResponse", result.text());
                                values.put("aiState", result.error() == null ? "SUCCEEDED" : "REJECTED");
                                if (result.error() != null) values.put("aiError", result.error());
                            })
                            .then(Mono.defer(() -> finish(command, result.error()))));
        });
    }

    private record Result(String text, String error) {}

    private Mono<InboundCommand> finish(InboundCommand command, String error) {
        command.getSpec()
                .setWriteState(
                        error == null ? InboundCommand.WriteState.SUCCEEDED : InboundCommand.WriteState.REJECTED);
        command.getSpec().setErrorCode(error);
        return resources.update(command);
    }

    private static String diff(DraftReview.View view) {
        var text = new StringBuilder();
        if (!view.beforeTitle().equals(view.title()))
            text.append("标题：")
                    .append(view.beforeTitle())
                    .append(" → ")
                    .append(view.title())
                    .append("\n");
        for (var change : view.changes())
            if (!change.kind().equals("UNCHANGED"))
                text.append(change.kind().equals("ADDED") ? "+ 新增：\n" : "- 删除：\n")
                        .append(change.text())
                        .append("\n");
        return text.isEmpty() ? "正文未变化。" : text.toString();
    }
}
