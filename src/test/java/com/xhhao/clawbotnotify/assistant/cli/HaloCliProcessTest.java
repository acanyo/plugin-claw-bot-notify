package com.xhhao.clawbotnotify.assistant.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HaloCliProcessTest {
    @TempDir
    Path temp;

    Path script(String body) throws Exception {
        Path file = Files.createTempFile(temp, "cli-", ".py");
        Files.writeString(file, "#!/usr/bin/env python3\n" + body);
        assertThat(file.toFile().setExecutable(true)).isTrue();
        return file;
    }

    @Test
    void executesLiteralArgumentsWithoutShellExpansion() throws Exception {
        Path executable = script(
                "import json,sys,os\nprint(json.dumps({'args':sys.argv[1:],'masterKey':os.environ.get('CLAWBOT_MASTER_KEY')}))\n");
        Path marker = temp.resolve("injected");
        String content = "--content=$(touch " + marker + "); `pwd`\n--profile=other";
        var output = new HaloCliProcess(executable.toString(), "clawbot", Duration.ofSeconds(5))
                .run(List.of("moment", "create", content))
                .block();
        assertThat(output.path("args").get(2).asText()).isEqualTo(content);
        assertThat(output.path("args").get(3).asText()).isEqualTo("--profile=clawbot");
        assertThat(output.path("masterKey").isNull()).isTrue();
        assertThat(marker).doesNotExist();
    }

    @Test
    void nonZeroExitDoesNotExposeStdoutOrStderr() throws Exception {
        Path executable =
                script("import sys\nprint('private-token')\nprint('private-key',file=sys.stderr)\nsys.exit(1)\n");
        assertThatThrownBy(() -> new HaloCliProcess(executable.toString(), "clawbot", Duration.ofSeconds(5))
                        .run(List.of())
                        .block())
                .hasMessage("CLI_FAILED")
                .hasNoCause();
    }

    @Test
    void rejectsNonJsonAndOversizedOutput() throws Exception {
        Path invalid = script("print('private-token')\n");
        assertThatThrownBy(() -> new HaloCliProcess(invalid.toString(), "clawbot", Duration.ofSeconds(5))
                        .run(List.of())
                        .block())
                .hasMessage("CLI_OUTPUT_INVALID");
        Path oversized = script("print('x'*300000)\n");
        assertThatThrownBy(() -> new HaloCliProcess(oversized.toString(), "clawbot", Duration.ofSeconds(5))
                        .run(List.of())
                        .block())
                .hasMessageStartingWith("CLI_")
                .hasNoCause();
    }

    @Test
    void genericExecutionSupportsStdinAndPlainTextOutput() throws Exception {
        Path executable =
                script("import sys,json\nprint(json.dumps({'stdin':sys.stdin.read(),'args':sys.argv[1:]}))\n");
        var cli = new HaloCliProcess(executable.toString(), "clawbot", Duration.ofSeconds(5));
        var result = cli.invoke(List.of("shop", "product", "create", "--stdin"), "original payload")
                .block();
        assertThat(result.exitCode()).isZero();
        var node = (com.fasterxml.jackson.databind.JsonNode) result.output();
        assertThat(node.path("stdin").asText()).isEqualTo("original payload");
        assertThat(node.path("args").size()).isEqualTo(4);
        Path plain = script("print('Commands:\\n  comment  Comments')\n");
        assertThat(new HaloCliProcess(plain.toString(), "clawbot", Duration.ofSeconds(5))
                        .help(List.of())
                        .block())
                .contains("Commands:");
    }

    @Test
    void timeoutStopsProcess() throws Exception {
        Path pid = temp.resolve("pid");
        Path executable = script("import os,time\nopen('" + pid + "','w').write(str(os.getpid()))\ntime.sleep(20)\n");
        assertThatThrownBy(() -> new HaloCliProcess(executable.toString(), "clawbot", Duration.ofMillis(500))
                        .run(List.of())
                        .block())
                .hasMessage("CLI_TIMEOUT");
        if (Files.exists(pid)) {
            long process = Long.parseLong(Files.readString(pid));
            for (int i = 0;
                    i < 30
                            && ProcessHandle.of(process)
                                    .map(ProcessHandle::isAlive)
                                    .orElse(false);
                    ++i) Thread.sleep(20);
            assertThat(ProcessHandle.of(process).map(ProcessHandle::isAlive).orElse(false))
                    .isFalse();
        }
    }

    @Test
    void cancellingReactiveRequestStopsProcess() throws Exception {
        Path pid = temp.resolve("cancel-pid");
        Path executable = script("import os,time\nopen('" + pid + "','w').write(str(os.getpid()))\ntime.sleep(20)\n");
        var subscription = new HaloCliProcess(executable.toString(), "clawbot", Duration.ofSeconds(30))
                .run(List.of())
                .subscribe();
        for (int i = 0; i < 100 && !Files.exists(pid); ++i) Thread.sleep(20);
        assertThat(pid).exists();
        long process = Long.parseLong(Files.readString(pid));
        subscription.dispose();
        for (int i = 0;
                i < 100 && ProcessHandle.of(process).map(ProcessHandle::isAlive).orElse(false);
                ++i) Thread.sleep(20);
        assertThat(ProcessHandle.of(process).map(ProcessHandle::isAlive).orElse(false))
                .isFalse();
    }
}
