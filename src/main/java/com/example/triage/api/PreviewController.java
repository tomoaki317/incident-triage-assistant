package com.example.triage.api;

import com.example.triage.dto.IncidentInput;
import com.example.triage.dto.PreviewResponse;
import com.example.triage.service.PreviewService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PreviewController {
    private final PreviewService previews;

    public PreviewController(PreviewService previews) {
        this.previews = previews;
    }

    @PostMapping(value = "/api/previews", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PreviewResponse> create(@RequestBody IncidentInput input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(previews.preview(input));
    }
}
