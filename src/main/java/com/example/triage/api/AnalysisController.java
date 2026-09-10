package com.example.triage.api;

import com.example.triage.dto.*;
import com.example.triage.service.AnalysisService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class AnalysisController {
    private final AnalysisService analyses;
    public AnalysisController(AnalysisService analyses) { this.analyses = analyses; }

    @PostMapping(value = "/api/analyses", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AnalysisResponse> analyze(@RequestBody AnalyzeRequest input, HttpServletRequest request) {
        var session = request.getSession(false);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(analyses.analyze(input.previewId(), session == null ? null : session.getId()));
    }
}
