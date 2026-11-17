package com.xhhao.clawbotnotify.halo;

import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.extension.ReactiveExtensionClient;

/** Resolves an explicitly selected Halo author without creating or modifying user accounts. */
@Component
public class MomentAuthors {
    private final ReactiveExtensionClient resources;

    public MomentAuthors(ReactiveExtensionClient resources) {
        this.resources = resources;
    }

    public record Author(String username, String displayName) {
        public String label() {
            return displayName == null || displayName.isBlank()
                    ? username
                    : displayName.replaceAll("\\s+", " ").strip() + "（" + username + "）";
        }
    }

    public Mono<Author> resolve(String username) {
        if (username == null || username.isBlank())
            return Mono.error(new IllegalStateException("HALO_MOMENT_AUTHOR_REQUIRED"));
        if (username.length() > 253
                || username.codePoints().anyMatch(Character::isISOControl)
                || Set.of("anonymousUser", "ghost").contains(username))
            return Mono.error(new IllegalStateException("HALO_MOMENT_AUTHOR_UNAVAILABLE"));
        return resources
                .fetch(User.class, username)
                .filter(user -> user.getMetadata().getDeletionTimestamp() == null && user.getSpec() != null)
                .switchIfEmpty(Mono.error(new IllegalStateException("HALO_MOMENT_AUTHOR_UNAVAILABLE")))
                .map(user ->
                        new Author(user.getMetadata().getName(), user.getSpec().getDisplayName()));
    }

    public Mono<Author> verify(Author author) {
        if (author == null) return Mono.error(new IllegalStateException("HALO_MOMENT_AUTHOR_REQUIRED"));
        return resolve(author.username());
    }

    public static boolean publication(String tool, Map<String, Object> input) {
        return tool.equals("moment_create")
                || tool.equals("moment_create_images")
                || tool.equals("halo_cli")
                        && input.get("arguments") instanceof java.util.List<?> arguments
                        && arguments.size() >= 2
                        && arguments.subList(0, 2).equals(java.util.List.of("moment", "create"));
    }
}
