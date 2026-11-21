package com.hackathon.alerting.ingest.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileTailerTest {

    @TempDir
    Path dir;

    private final List<String> lines = new ArrayList<>();

    @Test
    void startsAtTheEndAndDeliversOnlyCompleteNewLines() throws IOException {
        Path file = dir.resolve("app.log");
        append(file, "old line\n");
        try (FileTailer tailer = new FileTailer(file, lines::add, true)) {
            append(file, "first\nsecond\r\nhalf");
            tailer.poll();
            assertThat(lines).containsExactly("first", "second");

            append(file, " done\n\n");
            tailer.poll();
            assertThat(lines).containsExactly("first", "second", "half done");
        }
    }

    @Test
    void aFileThatAppearsLaterIsReadFromTheStart() throws IOException {
        Path file = dir.resolve("later.log");
        try (FileTailer tailer = new FileTailer(file, lines::add, true)) {
            assertThat(tailer.poll()).isZero();
            append(file, "a\nb\n");
            tailer.poll();
            assertThat(lines).containsExactly("a", "b");
        }
    }

    @Test
    void rotationDrainsTheOldFileThenReadsTheNewOneInOrder() throws IOException {
        Path file = dir.resolve("app.log");
        append(file, "");
        try (FileTailer tailer = new FileTailer(file, lines::add, true)) {
            append(file, "1\n2\n");
            tailer.poll();
            append(file, "3\n");                              // written just before rotation, not polled yet
            Files.move(file, dir.resolve("app.log.1"));
            append(file, "4\n5\n");                           // new file at the same path
            tailer.poll();
            append(file, "6\n");
            tailer.poll();
        }
        assertThat(lines).containsExactly("1", "2", "3", "4", "5", "6");
    }

    @Test
    void truncationRestartsFromTheBeginning() throws IOException {
        Path file = dir.resolve("app.log");
        append(file, "");
        try (FileTailer tailer = new FileTailer(file, lines::add, true)) {
            append(file, "a long line before truncation\n");
            tailer.poll();
            Files.writeString(file, "x\n", StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            tailer.poll();
        }
        assertThat(lines).containsExactly("a long line before truncation", "x");
    }

    @Test
    void aRejectedLineIsOfferedAgainAndNothingIsSkipped() throws IOException {
        Path file = dir.resolve("app.log");
        append(file, "");
        boolean[] kafkaDown = {true};
        try (FileTailer tailer = new FileTailer(file, line -> {
            if (kafkaDown[0] && line.equals("b")) {
                throw new IllegalStateException("Kafka is down");
            }
            lines.add(line);
        }, true)) {
            append(file, "a\nb\nc\n");
            assertThatThrownBy(tailer::poll).hasMessage("Kafka is down");
            assertThat(lines).containsExactly("a");

            kafkaDown[0] = false;
            tailer.poll();
        }
        assertThat(lines).containsExactly("a", "b", "c");
    }

    private static void append(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
