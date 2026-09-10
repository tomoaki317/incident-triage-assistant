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

    public PreviewService(InputValidator validator, MaskingService masking) {
        this.validator = validator;
        this.masking = masking;
    }

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
