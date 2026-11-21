package com.hackathon.alerting.runbooks;

import com.hackathon.alerting.support.KafkaTestConfiguration;
import com.hackathon.alerting.support.PostgresTestConfiguration;
import com.hackathon.alerting.support.RedisTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 10 MB upload limit ({@code spring.servlet.multipart.max-file-size}, AGENTS.md §4) is enforced by the servlet
 * container's multipart parser, which MockMvc bypasses — so this test runs a real server on a random port (with the full
 * infrastructure: without a broker, Kafka admin delays startup by ~30 s).
 *
 * <p>For a rejected upload Spring answers 413 {@code application/problem+json}, but Tomcat then closes the connection
 * at once (it does not read the rest of a refused body), so the response body may arrive truncated. The test therefore
 * asserts what reliably reaches a client — status line and {@code Content-Type} — through {@link HttpURLConnection},
 * which reads them without needing the full body.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestConfiguration.class, KafkaTestConfiguration.class, RedisTestConfiguration.class})
class RunbookUploadLimitTest {

    private static final int LIMIT_BYTES = 10 * 1024 * 1024;

    @LocalServerPort
    private int port;

    @Test
    void uploadOverTenMegabytesIsRejectedWith413ProblemDetails() throws IOException {
        Response response = upload("too-big.pdf", new byte[LIMIT_BYTES + 1024]);

        assertThat(response.status()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
        assertThat(MediaType.parseMediaType(response.contentType()).isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
    }

    @Test
    void uploadJustUnderTheLimitIsAccepted() throws IOException {
        Response response = upload("just-fits.pdf", new byte[LIMIT_BYTES - 1024]);

        assertThat(response.status()).isEqualTo(HttpStatus.CREATED.value());
        assertThat(response.body()).contains("\"fileName\":\"just-fits.pdf\"");
    }

    /** Posts {@code multipart/form-data} with one {@code file} part, with a fixed Content-Length (no chunking). */
    private Response upload(String fileName, byte[] content) throws IOException {
        String boundary = "----runbook-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream(content.length + 512);
        body.writeBytes(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpURLConnection connection = (HttpURLConnection) URI.create("http://localhost:" + port + "/api/runbooks").toURL().openConnection();
        try {
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setFixedLengthStreamingMode(body.size());
            try (OutputStream out = connection.getOutputStream()) {
                body.writeTo(out);
            } catch (IOException e) {
                // The server may refuse the rest of an oversized body; the response is still readable below.
            }
            int status = connection.getResponseCode();
            return new Response(status, connection.getContentType(), readQuietly(connection, status));
        } finally {
            connection.disconnect();
        }
    }

    /** The response body, or what arrived of it before the server closed the connection. */
    private static String readQuietly(HttpURLConnection connection, int status) {
        try (InputStream in = status < 400 ? connection.getInputStream() : connection.getErrorStream()) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private record Response(int status, String contentType, String body) {
    }
}
