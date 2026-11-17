package com.xhhao.clawbotnotify.assistant;

import run.halo.aifoundation.chat.FinishReason;
import run.halo.aifoundation.chat.GenerateTextResult;
import run.halo.aifoundation.message.ModelMessageRole;
import run.halo.aifoundation.part.PartType;

/** Distinguish terminal answers from tool preambles and provider-truncated output. */
public final class AssistantGenerationPolicy {
    private AssistantGenerationPolicy() {}

    public record Answer(String text, String errorCode) {}

    public static Answer answer(GenerateTextResult result) {
        FinishReason finish = result.getFinishReason();
        String text = result.getText();
        var steps = result.getSteps();
        if (steps != null && !steps.isEmpty()) {
            var last = steps.getLast();
            finish = last.getFinishReason() == null ? finish : last.getFinishReason();
            text = last.getText();
            if (last.getToolCalls() != null && !last.getToolCalls().isEmpty()) return unfinished();
        }
        if (result.getResponseMessages() != null
                && !result.getResponseMessages().isEmpty()) {
            var last = result.getResponseMessages().getLast();
            if (last.getRole() == ModelMessageRole.TOOL) return unfinished();
            if (last.getRole() == ModelMessageRole.ASSISTANT && last.getContent() != null) {
                if (last.getContent().stream().anyMatch(part -> PartType.TOOL_CALL.equals(part.getType())))
                    return unfinished();
                if (steps == null || steps.isEmpty())
                    text = last.getContent().stream()
                            .filter(part -> PartType.TEXT.equals(part.getType()))
                            .map(part -> part.getText() == null ? "" : part.getText())
                            .collect(java.util.stream.Collectors.joining());
            }
        }
        if (finish == FinishReason.TOOL_CALLS) return unfinished();
        if (finish == FinishReason.LENGTH)
            return new Answer(
                    "模型供应商截断了本次输出，答复未完成。可以要求继续生成。" + (text == null || text.isBlank() ? "" : "\n\n已生成部分：\n" + text),
                    "AI_PROVIDER_OUTPUT_LIMIT");
        if (finish == FinishReason.CONTENT_FILTER || finish == FinishReason.ERROR)
            return new Answer("模型未能完成本次答复，请查看模型配置和运行记录。", "AI_GENERATION_INCOMPLETE");
        if (text == null || text.isBlank()) return new Answer("本次没有生成最终文字答复，请重新描述需求或检查模型配置。", "AI_EMPTY_RESULT");
        return new Answer(text, null);
    }

    private static Answer unfinished() {
        return new Answer("本次工具调用未返回完整的调用与结果，模型未能继续生成最终答复，任务尚未完成。请重试或检查模型工具调用能力。", "AI_GENERATION_INCOMPLETE");
    }
}
