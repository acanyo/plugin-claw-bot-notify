package com.xhhao.clawbotnotify.halo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xhhao.clawbotnotify.comment.CommentDiscovery;
import com.xhhao.clawbotnotify.model.WechatBinding;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Reply;
import run.halo.app.core.user.service.RoleService;

/** Business content is always read through the owner's PAT, including pending and hidden comments. */
@Component
public class HaloCommentReader {
    private final HaloIdentityVerifier halo;
    private final RoleService roles;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    public HaloCommentReader(HaloIdentityVerifier halo, RoleService roles) {
        this.halo = halo;
        this.roles = roles;
    }

    public Mono<Void> probe(WechatBinding binding, String token) {
        return authorized(binding, token)
                .then(halo.get(token, "/apis/content.halo.run/v1alpha1/comments?page=1&size=1", false))
                .then();
    }

    public Mono<Comment> get(WechatBinding binding, String token, String name) {
        return parent(binding, token, name).filter(comment -> CommentDiscovery.eligible(binding, comment));
    }

    /** A new reply may belong to a comment older than the binding's notification window. */
    public Mono<Comment> parent(WechatBinding binding, String token, String name) {
        return authorized(binding, token)
                .then(halo.get(token, "/apis/content.halo.run/v1alpha1/comments/" + segment(name), true))
                .flatMap(body -> {
                    try {
                        return Mono.just(json.treeToValue(body, Comment.class));
                    } catch (Exception e) {
                        return Mono.error(new IllegalStateException("HALO_COMMENT_INVALID"));
                    }
                })
                .filter(comment -> binding.getSpec().isEnabled() && CommentDiscovery.supported(comment));
    }

    public Mono<String> title(Comment comment, String token) {
        var ref = comment.getSpec().getSubjectRef();
        String plural = "Post".equals(ref.getKind()) ? "posts" : "singlepages";
        String fallback = "Post".equals(ref.getKind()) ? "文章（未取得标题）" : "独立页面（未取得标题）";
        return halo.get(token, "/apis/content.halo.run/v1alpha1/" + plural + "/" + segment(ref.getName()), true)
                .map(body -> body.path("spec").path("title").asText(fallback))
                .defaultIfEmpty(fallback)
                .onErrorReturn(fallback);
    }

    public Mono<Reply> reply(WechatBinding binding, String token, String commentName, String replyName) {
        return authorized(binding, token)
                .then(halo.get(token, "/apis/content.halo.run/v1alpha1/replies/" + segment(replyName), true))
                .flatMap(body -> {
                    try {
                        return Mono.just(json.treeToValue(body, Reply.class));
                    } catch (Exception e) {
                        return Mono.error(new IllegalStateException("HALO_REPLY_INVALID"));
                    }
                })
                .filter(reply -> reply.getMetadata().getDeletionTimestamp() == null
                        && reply.getSpec() != null
                        && commentName.equals(reply.getSpec().getCommentName()));
    }

    private Mono<Void> authorized(WechatBinding binding, String token) {
        String username = binding.getSpec().getHaloUsername();
        // /users/- exposes PAT roles, which may be narrower than the account's roles.
        // Check current account membership separately; content access still uses the PAT.
        return halo.verify(token, username)
                .then(roles.getRolesByUsername(username).any("super-role"::equals))
                .flatMap(admin -> admin ? Mono.empty() : Mono.error(new IllegalStateException("HALO_ADMIN_REQUIRED")));
    }

    private static String segment(String name) {
        return UriUtils.encodePathSegment(name, StandardCharsets.UTF_8);
    }
}
