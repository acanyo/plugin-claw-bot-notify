package com.xhhao.clawbotnotify.halo;

import static org.assertj.core.api.Assertions.*;

import com.xhhao.clawbotnotify.support.ExtensionFixture;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import run.halo.app.core.extension.User;
import run.halo.app.extension.Metadata;

class MomentAuthorsTest {
    @Test
    void resolvesAnySelectedUserWithoutCreatingOrModifyingAnAccount() {
        var resources = new ExtensionFixture();
        var user = new User();
        var metadata = new Metadata();
        metadata.setName("my-bot");
        user.setMetadata(metadata);
        user.setSpec(new User.UserSpec());
        user.getSpec().setDisplayName("自己的机器人");
        user.getSpec().setDisabled(true);
        resources.put(user, true);
        var authors = new MomentAuthors(resources.client);
        assertThat(authors.resolve("my-bot").block()).isEqualTo(new MomentAuthors.Author("my-bot", "自己的机器人"));
        assertThat(authors.resolve("my-bot").block().label()).isEqualTo("自己的机器人（my-bot）");
        assertThat(resources.updates).hasValue(1);
    }

    @Test
    void missingAnonymousOrDeletedUsersAreRejectedWithoutFallbackOrAccountCreation() {
        var resources = new ExtensionFixture();
        var authors = new MomentAuthors(resources.client);
        assertThatThrownBy(() -> authors.resolve(null).block()).hasMessage("HALO_MOMENT_AUTHOR_REQUIRED");
        assertThatThrownBy(() -> authors.verify(null).block()).hasMessage("HALO_MOMENT_AUTHOR_REQUIRED");
        for (String name : new String[] {"no-such-user", "anonymousUser", "ghost"})
            assertThatThrownBy(() -> authors.resolve(name).block()).hasMessage("HALO_MOMENT_AUTHOR_UNAVAILABLE");
        assertThat(resources.updates).hasValue(0);
        var user = new User();
        var metadata = new Metadata();
        metadata.setName("deleted-bot");
        metadata.setDeletionTimestamp(Instant.now());
        user.setMetadata(metadata);
        resources.put(user, true);
        assertThatThrownBy(() -> authors.resolve("deleted-bot").block()).hasMessage("HALO_MOMENT_AUTHOR_UNAVAILABLE");
    }
}
