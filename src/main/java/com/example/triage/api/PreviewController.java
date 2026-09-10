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
    public ResponseEntity<PreviewResponse> create(@RequestBody IncidentInput input, jakarta.servlet.http.HttpSession session) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(previews.create(input, session.getId()));
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/api/previews/{id}")
    public ResponseEntity<Void> delete(@org.springframework.web.bind.annotation.PathVariable String id,
            jakarta.servlet.http.HttpServletRequest request) {
        var session = request.getSession(false);
        previews.delete(id, session == null ? null : session.getId());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
