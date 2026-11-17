package com.xhhao.clawbotnotify.halo;

import com.xhhao.clawbotnotify.comment.ReplyDiscovery;
import com.xhhao.clawbotnotify.model.WechatBinding;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Reply;

/** Resolves the exact persisted target through the binding owner's PAT. */
@Component
public class CommentTargetReader {
    private final HaloCommentReader comments;

    public CommentTargetReader(HaloCommentReader comments) {
        this.comments = comments;
    }

    public record Target(Comment comment, Reply reply) {
        public Comment.BaseCommentSpec content() {
            return reply == null ? comment.getSpec() : reply.getSpec();
        }

        public boolean approved() {
            return Boolean.TRUE.equals(comment.getSpec().getApproved())
                    && Boolean.TRUE.equals(content().getApproved());
        }
    }

    public Mono<Target> get(WechatBinding binding, String token, String commentName, String replyName) {
        if (replyName == null) return comments.get(binding, token, commentName).map(root -> new Target(root, null));
        return comments.parent(binding, token, commentName)
                .flatMap(root -> comments.reply(binding, token, commentName, replyName)
                        .filter(reply -> ReplyDiscovery.eligible(binding, reply))
                        .map(reply -> new Target(root, reply)));
    }
}
