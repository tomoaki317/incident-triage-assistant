package com.example.triage.validation;

import com.example.triage.dto.IncidentInput;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class InputValidator {
    public static boolean missing(String value) {
        return value == null || value.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c));
    }

    public void validate(IncidentInput input) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (input == null) throw new InputValidationException(Map.of("symptom", "必須項目です。"));
        if (missing(input.symptom())) errors.put("symptom", "必須項目です。");
        limit(errors, "symptom", input.symptom(), 2000);
        limit(errors, "log", input.log(), 20000);
        if (missing(input.log()) && missing(input.logStatus())) errors.put("log_status", "ログがない場合は取得状況が必須です。");
        choice(errors, "log_status", input.logStatus(), Set.of("未取得", "取得できない", "該当ログなし"));
        var c = input.context();
        if (c != null) {
            limit(errors, "context.occurred_at", c.occurredAt(), 100);
            limit(errors, "context.impact", c.impact(), 1000);
            limit(errors, "context.recent_changes", c.recentChanges(), 1000);
            limit(errors, "context.checks_performed", c.checksPerformed(), 2000);
            limit(errors, "context.destination", c.destination(), 200);
            choice(errors, "context.environment", c.environment(), Set.of("本番", "検証", "開発", "不明"));
            choice(errors, "context.ongoing_status", c.ongoingStatus(), Set.of("継続中", "解消済み", "不明"));
        }
        if (!errors.isEmpty()) throw new InputValidationException(errors);
    }

    private void limit(Map<String, String> errors, String field, String value, int max) {
        if (value != null && value.codePointCount(0, value.length()) > max)
            errors.put(field, max + "文字以内にしてください。該当時刻や関連スタックトレースへ抜粋してください。");
    }

    private void choice(Map<String, String> errors, String field, String value, Set<String> choices) {
        if (!missing(value) && !choices.contains(value)) errors.put(field, "定義された値を選択してください。");
    }
}
