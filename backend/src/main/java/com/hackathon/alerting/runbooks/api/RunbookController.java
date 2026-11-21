package com.hackathon.alerting.runbooks.api;

import com.hackathon.alerting.runbooks.repository.RunbookFile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Runbooks CRUD, multipart upload, and raw file download ({@code /api/runbooks}, tag {@code runbooks} in {@code frontend/openapi.yaml}).
 */
@RestController
@RequestMapping("/api/runbooks")
@Tag(name = "runbooks")
public class RunbookController {

    private final RunbookCrudService runbooks;

    public RunbookController(RunbookCrudService runbooks) {
        this.runbooks = runbooks;
    }

    @GetMapping
    @Operation(summary = "Runbooks without file contents")
    public List<RunbookDto> list(@RequestParam(required = false) Long teamId) {
        return runbooks.list(teamId);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Upload a runbook file of any format (max 10 MB)")
    public RunbookDto create(@RequestPart("file") MultipartFile file,
                             @RequestParam(required = false) Long teamId,
                             @RequestParam(required = false) String condition) {
        return runbooks.create(file, teamId, condition);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Runbook metadata")
    public RunbookDto get(@PathVariable long id) {
        return runbooks.get(id);
    }

    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Update team, condition and optionally replace the file")
    public RunbookDto update(@PathVariable long id,
                             @RequestPart(value = "file", required = false) MultipartFile file,
                             @RequestParam(required = false) Long teamId,
                             @RequestParam(required = false) String condition) {
        return runbooks.update(id, file, teamId, condition);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a runbook")
    public void delete(@PathVariable long id) {
        runbooks.delete(id);
    }

    @GetMapping("/{id}/file")
    @Operation(summary = "Download the file as uploaded")
    public ResponseEntity<byte[]> download(@PathVariable long id) {
        RunbookFile file = runbooks.downloadFile(id);
        MediaType mediaType = MediaTypeFactory.getMediaType(file.fileName())
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(file.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(file.content());
    }
}
