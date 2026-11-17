package com.xhhao.clawbotnotify.comment;

import java.util.Set;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.user.service.RoleService;
import run.halo.app.extension.ReactiveExtensionClient;

/** Identifies current moderators so their own replies do not generate visitor notifications. */
@Component
public class CommentAuthors {
    private final ReactiveExtensionClient resources;
    private final RoleService roles;

    public CommentAuthors(ReactiveExtensionClient resources, RoleService roles) {
        this.resources = resources;
        this.roles = roles;
    }

    public Mono<Boolean> moderator(Comment.CommentOwner owner) {
        if (owner == null || !User.KIND.equals(owner.getKind()) || owner.getName() == null) return Mono.just(false);
        return resources
                .fetch(User.class, owner.getName())
                .filter(user -> user.getMetadata().getDeletionTimestamp() == null
                        && user.getSpec() != null
                        && !Boolean.TRUE.equals(user.getSpec().getDisabled()))
                .flatMap(user -> roles.getRolesByUsername(owner.getName())
                        .collectList()
                        .flatMap(assigned -> assigned.contains("super-role")
                                ? Mono.just(true)
                                : roles.contains(assigned, Set.of("role-template-manage-comments"))))
                .defaultIfEmpty(false);
    }
}
