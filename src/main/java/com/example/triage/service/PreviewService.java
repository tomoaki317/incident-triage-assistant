package com.example.triage.service;

import com.example.triage.dto.*;
import com.example.triage.masking.MaskingService;
import com.example.triage.validation.*;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PreviewService {
    private final InputValidator validator;
    private final MaskingService masking;
    private final com.example.triage.runtime.PreviewStore store;

    public PreviewService(InputValidator validator, MaskingService masking) {
        this(validator, masking, new com.example.triage.runtime.PreviewStore());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PreviewService(InputValidator validator, MaskingService masking, com.example.triage.runtime.PreviewStore store) {
        this.validator = validator;
        this.masking = masking;
        this.store = store;
    }

    public PreviewResponse create(IncidentInput input, String owner) {
        var prepared = preview(input);
        var fields = new java.util.HashSet<String>();
        fields.add("symptom");
        if (!InputValidator.missing(input.logStatus())) fields.add("log_status");
        var c = input.context();
        if (c != null) {
            String[] ids = {"occurred_at", "environment", "impact", "ongoing_status", "recent_changes", "checks_performed", "destination"};
            String[] values = {c.occurredAt(), c.environment(), c.impact(), c.ongoingStatus(), c.recentChanges(), c.checksPerformed(), c.destination()};
            for (int i = 0; i < ids.length; i++)
                if (!InputValidator.missing(values[i])) fields.add("context." + ids[i]);
        }
        return store.save(prepared, new SourceReferences(fields, java.util.Set.copyOf(prepared.logLineIds())), owner);
    }

    public com.example.triage.runtime.PreviewStore.Snapshot get(String id, String owner) {
        return store.get(id, owner);
    }

    public void delete(String id, String owner) { store.delete(id, owner); }

    public PreviewResponse preview(IncidentInput input) {
        validator.validate(input);
        var masked = masking.mask(input);
        List<String> lines = new ArrayList<>();
        if (!masked.log().isEmpty()) {
            int count = masked.log().split("\\r\\n|\\r|\\n", -1).length;
            for (int i = 1; i <= count; i++) lines.add("log:L" + i);
        }
        return new PreviewResponse(masked, lines, "未選定（AI未接続・送信なし）", "障害の一次切り分け支援",
                List.of("マスキングは完全ではありません。送信予定の全項目を確認してください。",
                        "氏名、住所、電話番号、顧客ID、社内ホスト名などは確認・除去してください。",
                        "合成データを使用してください。最終判断は担当者が行い、緊急時は既存手順を優先してください。"));
    }
}
