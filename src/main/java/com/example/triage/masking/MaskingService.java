package com.example.triage.masking;

import com.example.triage.dto.*;
import com.example.triage.validation.InputValidator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class MaskingService {
    // Explicit labels delimit credentials; unlabelled arbitrary IDs are not secrets.
    private static final Pattern SECRETS = Pattern.compile(
            "(?im)(?<header>(?<![\\w-])[\"']?(?:authorization|cookie)[\"']?[\\t ]*[:=][\\t ]*)(?<headerValue>[^\\r\\n]+)"
            + "|(?<key>[\"']?\\b(?:api[-_]?key|password|passwd|pwd|connection[-_]?string)[\"']?[\\t ]*[:=][\\t ]*)(?<value>\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^\\s,;}]+)"
            + "|(?<connection>\\bjdbc:[^\\s\"'<>]+|\\b(?:postgres(?:ql)?|mysql|mongodb(?:\\+srv)?|sqlserver)://[^\\s\"'<>]+)"
            + "|(?<email>[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9](?:[A-Z0-9-]*[A-Z0-9])?(?:\\.[A-Z0-9](?:[A-Z0-9-]*[A-Z0-9])?)+)");

    public MaskedIncidentInput mask(IncidentInput input) {
        // This map lives only for this invocation and is never returned or stored.
        Map<String, String> aliases = new LinkedHashMap<>();
        var c = input.context();
        var context = new IncidentContext(
                mask(c == null ? null : c.occurredAt(), aliases),
                mask(c == null ? null : c.environment(), aliases),
                mask(c == null ? null : c.impact(), aliases),
                mask(c == null ? null : c.ongoingStatus(), aliases),
                mask(c == null ? null : c.recentChanges(), aliases),
                mask(c == null ? null : c.checksPerformed(), aliases),
                mask(c == null ? null : c.destination(), aliases));
        return new MaskedIncidentInput(mask(input.symptom(), aliases),
                InputValidator.missing(input.log()) ? "" : mask(input.log(), aliases),
                mask(input.logStatus(), aliases), context);
    }

    private String mask(String text, Map<String, String> aliases) {
        if (InputValidator.missing(text)) return "不明";
        var matcher = SECRETS.matcher(text);
        var result = new StringBuilder();
        while (matcher.find()) {
            String prefix = "";
            String secret = matcher.group();
            if (matcher.group("header") != null) {
                prefix = matcher.group("header");
                secret = matcher.group("headerValue");
            } else if (matcher.group("key") != null) {
                prefix = matcher.group("key");
                secret = matcher.group("value");
            }
            if (secret.length() >= 2 && (secret.startsWith("\"") || secret.startsWith("'")))
                secret = secret.substring(1, secret.length() - 1);
            String alias = aliases.computeIfAbsent(secret, ignored -> "[MASKED_" + (aliases.size() + 1) + "]");
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(prefix + alias));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
