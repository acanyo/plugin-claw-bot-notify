package com.xhhao.clawbotnotify.storage;

import static org.assertj.core.api.Assertions.*;

import com.xhhao.clawbotnotify.assistant.cli.ManagedCliRuntime;
import com.xhhao.clawbotnotify.credential.CredentialCipher;
import com.xhhao.clawbotnotify.credential.MasterKeyStore;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;

class ClawBotDataDirectoryTest {
    @TempDir
    Path temp;

    @Test
    void springWiringUsesTheHostWorkDirectoryAndAutomaticallyCreatesTheKey() {
        try (var host = new GenericApplicationContext();
                var plugin = new AnnotationConfigApplicationContext()) {
            host.getEnvironment()
                    .getPropertySources()
                    .addFirst(new MapPropertySource("host-config", Map.of("halo.work-dir", temp.toString())));
            host.refresh();
            plugin.setParent(host);
            plugin.register(
                    ClawBotDataDirectory.class, MasterKeyStore.class, CredentialCipher.class, ManagedCliRuntime.class);
            plugin.refresh();
            assertThat(plugin.getBean(ClawBotDataDirectory.class).root()).isEqualTo(temp.resolve("clawbot"));
            assertThat(plugin.getBean(CredentialCipher.class).available()).isTrue();
            assertThat(plugin.getBean(ManagedCliRuntime.class).status().mode()).isEqualTo("MANAGED");
            assertThat(temp.resolve("clawbot/master.key")).isRegularFile();
        }
    }
}
