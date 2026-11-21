package com.hackathon.alerting.runbooks;

import com.hackathon.alerting.common.web.InvalidRequestException;
import com.hackathon.alerting.common.web.NotFoundException;
import com.hackathon.alerting.runbooks.api.RunbookController;
import com.hackathon.alerting.runbooks.api.RunbookCrudService;
import com.hackathon.alerting.runbooks.api.RunbookDto;
import com.hackathon.alerting.runbooks.repository.RunbookFile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RunbookController.class)
class RunbookControllerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RunbookCrudService runbooks;

    @Test
    void listAndGetReturnMetadataDtos() throws Exception {
        JsonNode cond = JSON.readTree("{\"path\":\"status\",\"op\":\"==\",\"value\":\"FAILED\"}");
        when(runbooks.list(10L)).thenReturn(List.of(
                new RunbookDto(1L, "restart.pdf", "/api/runbooks/1/file", 10L, cond),
                new RunbookDto(2L, "global.md", "/api/runbooks/2/file", null, null)
        ));
        when(runbooks.get(1L)).thenReturn(new RunbookDto(1L, "restart.pdf", "/api/runbooks/1/file", 10L, cond));

        mockMvc.perform(get("/api/runbooks").param("teamId", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].fileName").value("restart.pdf"))
                .andExpect(jsonPath("$[0].fileUrl").value("/api/runbooks/1/file"))
                .andExpect(jsonPath("$[0].teamId").value(10))
                .andExpect(jsonPath("$[0].condition.path").value("status"))
                .andExpect(jsonPath("$[1].teamId").isEmpty());

        mockMvc.perform(get("/api/runbooks/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileUrl").value("/api/runbooks/1/file"));
    }

    @Test
    void uploadAndUpdateAcceptMultipartFormData() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "restart.md", MediaType.TEXT_MARKDOWN_VALUE, "# Steps".getBytes(StandardCharsets.UTF_8));
        JsonNode cond = JSON.readTree("{\"path\":\"desk\",\"op\":\"==\",\"value\":\"rates\"}");
        RunbookDto created = new RunbookDto(3L, "restart.md", "/api/runbooks/3/file", 5L, cond);

        when(runbooks.create(any(), eq(5L), eq(cond.toString()))).thenReturn(created);
        when(runbooks.update(eq(3L), isNull(), isNull(), isNull()))
                .thenReturn(new RunbookDto(3L, "restart.md", "/api/runbooks/3/file", null, null));

        mockMvc.perform(multipart("/api/runbooks")
                        .file(file)
                        .param("teamId", "5")
                        .param("condition", cond.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(3))
                .andExpect(jsonPath("$.fileUrl").value("/api/runbooks/3/file"));

        mockMvc.perform(multipart(HttpMethod.PUT, "/api/runbooks/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamId").isEmpty())
                .andExpect(jsonPath("$.condition").isEmpty());
    }

    @Test
    void downloadDerivesContentTypeFromFileExtension() throws Exception {
        byte[] pdfBytes = "%PDF-1.4".getBytes(StandardCharsets.UTF_8);
        byte[] mdBytes = "# Runbook".getBytes(StandardCharsets.UTF_8);
        byte[] yamlBytes = "runbook: cpu".getBytes(StandardCharsets.UTF_8);
        byte[] customBytes = "custom binary".getBytes(StandardCharsets.UTF_8);
        when(runbooks.downloadFile(1L)).thenReturn(new RunbookFile("restart-workflow.pdf", pdfBytes));
        when(runbooks.downloadFile(2L)).thenReturn(new RunbookFile("notes.txt", "hello".getBytes(StandardCharsets.UTF_8)));
        when(runbooks.downloadFile(3L)).thenReturn(new RunbookFile("guide.runbookxyz", customBytes));
        when(runbooks.downloadFile(4L)).thenReturn(new RunbookFile("trade-rejection.md", mdBytes));
        when(runbooks.downloadFile(5L)).thenReturn(new RunbookFile("GUIDE.MARKDOWN", mdBytes));
        when(runbooks.downloadFile(6L)).thenReturn(new RunbookFile("high-cpu.yaml", yamlBytes));
        when(runbooks.downloadFile(7L)).thenReturn(new RunbookFile("alerts.yml", yamlBytes));

        mockMvc.perform(get("/api/runbooks/1/file"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"restart-workflow.pdf\"; filename*=UTF-8''restart-workflow.pdf"))
                .andExpect(content().bytes(pdfBytes));

        mockMvc.perform(get("/api/runbooks/2/file"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.TEXT_PLAIN));

        mockMvc.perform(get("/api/runbooks/3/file"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(content().bytes(customBytes));

        mockMvc.perform(get("/api/runbooks/4/file"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.TEXT_MARKDOWN))
                .andExpect(content().bytes(mdBytes));

        mockMvc.perform(get("/api/runbooks/5/file"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.TEXT_MARKDOWN));

        mockMvc.perform(get("/api/runbooks/6/file"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_YAML))
                .andExpect(content().bytes(yamlBytes));

        mockMvc.perform(get("/api/runbooks/7/file"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_YAML));
    }

    @Test
    void deleteAndErrorResponsesReturnExpectedStatuses() throws Exception {
        mockMvc.perform(delete("/api/runbooks/7"))
                .andExpect(status().isNoContent());
        verify(runbooks).delete(7L);

        doThrow(new NotFoundException("Runbook", 99L)).when(runbooks).get(99L);
        mockMvc.perform(get("/api/runbooks/99"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Runbook 99 not found"));

        when(runbooks.create(any(), isNull(), eq("bad"))).thenThrow(new InvalidRequestException("condition: invalid JSON"));
        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/runbooks").file(file).param("condition", "bad"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("condition: invalid JSON"));
    }
}
