<template>
  <section class="wechat-guide cb-section" aria-labelledby="wechat-guide-title">
    <div class="guide-intro">
      <h2 id="wechat-guide-title">在微信里这样处理</h2>
      <p>评论与楼中楼都有独立通知，引用即可选中对应内容。</p>
      <div class="quote-example">
        <span>引用一条评论或楼中楼通知</span>
        <strong>直接输入回复正文</strong>
        <small>支持 Markdown；待审核内容会先保存草稿，再单独确认。</small>
      </div>
    </div>
    <dl class="guide-commands">
      <div>
        <dt>AI 对话与站点管理</dt>
        <dd>
          启用后，可查询评论、生成周报、管理内容、插件、主题和备份。写操作先生成预览，引用并按提示确认后执行；通用操作使用“确认执行”。
        </dd>
      </div>
      <div>
        <dt>博客总结到瞬间</dt>
        <dd>
          发送“把本周博客总结成瞬间”，或在插件设置 →
          智能助手中选择按月、按周、按天，统一北京时间晚上 9
          点生成。默认报告包含真实统计、完成事项和 AI 原创寄语，引用发送“确认发布”。
        </dd>
      </div>
      <div>
        <dt>长文章创作</dt>
        <dd>
          最多 30,000 字。引用预览发送“查看第 N 段”“查看差异”或“修改
          要求”；核对后按版本号确认全文保存或发布。后台指令详情可查看完整正文和差异。
        </dd>
      </div>
      <div>
        <dt>发送微信图片</dt>
        <dd>
          引用图片回执，可要求上传 Halo
          附件、发布带图瞬间或设置文章封面。先预览、确认后执行。一次最多 9 张，每张 10 MiB，支持
          PNG/JPEG/GIF，加密暂存 24 小时。
        </dd>
      </div>
      <div>
        <dt>引用评论后发送 <code>AI 帮我回复</code></dt>
        <dd>
          先生成回复建议，引用建议可修改或确认发送。发“新对话”或 <code>/clear</code> 清空 AI
          对话上下文。
        </dd>
      </div>
      <div>
        <dt><code>待处理</code><code>查看 C-XXXXXX</code></dt>
        <dd>查看待处理列表，再按编号读取评论详情。</dd>
      </div>
      <div>
        <dt>引用后发送 <code>忽略</code> 或 <code>标记已处理</code></dt>
        <dd>更新插件中的处理状态，方便继续处理下一条。</dd>
      </div>
      <div>
        <dt>在 Halo 后台回复</dt>
        <dd>管理员发布回复后，这里的评论会自动标记为已处理。</dd>
      </div>
    </dl>
  </section>
</template>

<style scoped>
.wechat-guide {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 48px;
}
.guide-intro h2 {
  font-size: 16px;
  font-weight: 600;
}
.guide-intro > p {
  color: var(--cb-muted);
  font-size: 12px;
  margin-top: 7px;
}
.quote-example {
  margin-top: 20px;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 9px;
}
.quote-example > span {
  padding: 5px 10px;
  border-left: 2px solid var(--cb-border);
  background: var(--cb-subtle);
  color: var(--cb-muted);
  font-size: 12px;
}
.quote-example strong {
  font-size: 14px;
  font-weight: 500;
}
.quote-example small {
  color: var(--cb-muted);
  font-size: 12px;
  line-height: 1.8;
}
.guide-commands {
  display: grid;
  gap: 18px;
}
.guide-commands dt {
  font-size: 12px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}
.guide-commands dd {
  color: var(--cb-muted);
  font-size: 12px;
  line-height: 1.8;
  margin-top: 5px;
}
code {
  background: var(--cb-hover);
  border-radius: 5px;
  padding: 2px 6px;
  font:
    11px ui-monospace,
    'SFMono-Regular',
    Consolas,
    monospace;
}
@container clawbot (max-width: 680px) {
  .wechat-guide {
    grid-template-columns: 1fr;
    gap: 24px;
  }
}
</style>
