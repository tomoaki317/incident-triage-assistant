package com.example.triage.ai;

import com.example.triage.dto.MaskedIncidentInput;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Deterministic local fixture generation, not real incident analysis. */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "triage.ai.mode", havingValue = "stub", matchIfMissing = true)
public class StubAiClient implements AiClient {
    private final JsonMapper mapper = new JsonMapper();

    @Override public String analyze(MaskedIncidentInput input) {
        String[] lines = input.log().split("\\r\\n|\\r|\\n", -1);
        int duplicate = -1;
        for (int i = 0; i < lines.length; i++) if (lines[i].contains("Duplicate entry")) { duplicate = i; break; }
        boolean outside = input.symptom().contains("ネットワーク機器の侵害調査");
        boolean hypothesis = !outside && duplicate >= 0;
        String status = outside ? "out_of_scope" : hypothesis ? "hypotheses_available" : "insufficient_information";
        String reason = outside ? "Stub：ネットワーク機器の侵害調査は初版対象外です。"
                : hypothesis ? "Stub：ログにDuplicate entryの記載があります。" : "Stub：原因候補を提示できる情報が不足しています。";
        var facts = hypothesis ? List.of(Map.of("id", "F1", "statement", "ログにDuplicate entryの記載があります。",
                "source_type", "log", "source_ref", "log:L" + (duplicate + 1))) : List.of(Map.of("id", "F1",
                "statement", "障害事象の入力があります。", "source_type", "user_report", "source_ref", "symptom"));
        var checks = outside ? List.of() : List.of(Map.of("id", "C1", "action", "発生時刻と該当ログの対応を確認してください。",
                "purpose", "申告とログの関連を確認するため。", "priority", "high"));
        var hypotheses = hypothesis ? List.of(Map.of("id", "H1", "description", "一意制約違反の可能性があります。",
                "evidence_fact_ids", List.of("F1"), "unverified_assumptions", List.of("申告とログの対応は未確認です。"),
                "check_ids", List.of("C1"))) : List.of();
        var escalation = new LinkedHashMap<String, Object>();
        escalation.put("summary", reason);
        for (String key : List.of("occurred_at", "environment", "impact", "ongoing_status", "destination")) escalation.put(key, "不明");
        escalation.put("related_fact_ids", List.of("F1"));
        escalation.put("hypothesis_ids", hypothesis ? List.of("H1") : List.of());
        escalation.put("checks_performed", List.of());
        escalation.put("open_questions", outside ? List.of("既存の対応窓口を確認してください。") : List.of("発生時刻と該当ログの対応"));
        var result = new LinkedHashMap<String, Object>();
        result.put("schema_version", "1.0"); result.put("assessment_status", status);
        result.put("assessment_reason", reason); result.put("summary", reason);
        result.put("facts", facts); result.put("hypotheses", hypotheses); result.put("checks", checks);
        result.put("missing_information", outside ? List.of() : List.of(Map.of("item", "発生時刻と該当ログの対応", "reason", "調査対象を確認するため。")));
        result.put("escalation", escalation); result.put("references", List.of());
        return mapper.writeValueAsString(result);
    }
}
