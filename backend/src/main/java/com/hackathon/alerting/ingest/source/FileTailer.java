package com.hackathon.alerting.ingest.source;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Objects;

/**
 * Follows one file like {@code tail -F} and hands every complete new line to a {@link LineSink}. Not thread-safe:
 * one thread calls {@link #poll()} repeatedly.
 *
 * <ul>
 *   <li>Starts at the <b>end</b> of an existing file (only new lines); a file that appears later is read from the start.</li>
 *   <li>Only complete lines ({@code \n}-terminated) are delivered; a partly written last line waits for the next poll.</li>
 *   <li>Rotation (the path now points to another file): the old file is drained to its end first, then the new one is
 *       read from the start. Truncation (the file shrank): reading restarts at the beginning.</li>
 *   <li>If the sink throws, the position stays at the start of that line: the <b>same</b> line is offered again on the
 *       next poll, nothing is skipped.</li>
 * </ul>
 */
public class FileTailer implements AutoCloseable {

    /** Receives one line (without the line terminator). Throwing means "not taken, offer it again later". */
    @FunctionalInterface
    public interface LineSink {
        void accept(String line);
    }

    static final int CHUNK_SIZE = 64 * 1024;
    /** A line longer than this is delivered in pieces rather than blocking the file forever. */
    static final int MAX_LINE_BYTES = 1024 * 1024;

    private final Path path;
    private final LineSink sink;
    private FileChannel channel;
    private Object fileKey;
    private long position;

    /**
     * @param path        file to follow
     * @param sink        receives complete lines
     * @param startAtEnd  {@code true}: skip what an existing file already contains
     */
    public FileTailer(Path path, LineSink sink, boolean startAtEnd) {
        this.path = Objects.requireNonNull(path, "path");
        this.sink = Objects.requireNonNull(sink, "sink");
        if (startAtEnd && open()) {
            try {
                position = channel.size();
            } catch (IOException e) {
                position = 0;
            }
        }
    }

    /**
     * Reads everything new and delivers it.
     *
     * @return number of lines delivered
     * @throws RuntimeException whatever the sink threw; the line stays pending
     */
    public int poll() throws IOException {
        int delivered = 0;
        if (channel == null && !open()) {
            return 0;
        }
        if (channel.size() < position) {
            position = 0;   // truncated
        }
        delivered += drain();
        Object current = currentFileKey();
        // A missing path (deleted, not yet re-created) keeps the old channel until a new file appears.
        if (current != null && !current.equals(fileKey)) {
            // Rotated: the old file is fully read (drain() returned at its end), switch to the new one.
            closeChannel();
            if (open()) {
                delivered += drain();
            }
        }
        return delivered;
    }

    /** Delivers all complete lines from {@link #position} to the end of the current channel. */
    private int drain() throws IOException {
        int delivered = 0;
        ByteBuffer buffer = ByteBuffer.allocate(CHUNK_SIZE);
        byte[] pending = new byte[0];
        long readAt = position;
        while (true) {
            buffer.clear();
            int read = channel.read(buffer, readAt);
            if (read <= 0) {
                return delivered;
            }
            readAt += read;
            byte[] data = concat(pending, buffer.array(), read);
            int lineStart = 0;
            for (int i = 0; i < data.length; i++) {
                if (data[i] == '\n') {
                    deliver(data, lineStart, i);
                    position += i - lineStart + 1;
                    delivered++;
                    lineStart = i + 1;
                }
            }
            pending = Arrays.copyOfRange(data, lineStart, data.length);
            if (pending.length >= MAX_LINE_BYTES) {
                deliver(pending, 0, pending.length);
                position += pending.length;
                delivered++;
                pending = new byte[0];
            }
        }
    }

    private void deliver(byte[] data, int from, int to) {
        int end = to > from && data[to - 1] == '\r' ? to - 1 : to;
        String line = new String(data, from, end - from, StandardCharsets.UTF_8);
        if (!line.isBlank()) {
            sink.accept(line);
        }
    }

    private static byte[] concat(byte[] head, byte[] tail, int tailLength) {
        byte[] result = new byte[head.length + tailLength];
        System.arraycopy(head, 0, result, 0, head.length);
        System.arraycopy(tail, 0, result, head.length, tailLength);
        return result;
    }

    private boolean open() {
        try {
            channel = FileChannel.open(path, StandardOpenOption.READ);
            fileKey = keyOf(path);
            position = 0;
            return true;
        } catch (NoSuchFileException e) {
            return false;
        } catch (IOException e) {
            closeChannel();
            return false;
        }
    }

    private Object currentFileKey() {
        try {
            return keyOf(path);
        } catch (IOException e) {
            return null;
        }
    }

    /** Identity of the file behind the path (device + inode); falls back to the creation time where unsupported. */
    private static Object keyOf(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
        return attributes.fileKey() != null ? attributes.fileKey() : attributes.creationTime();
    }

    private void closeChannel() {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
        }
        channel = null;
        fileKey = null;
    }

    @Override
    public void close() {
        closeChannel();
    }
}
