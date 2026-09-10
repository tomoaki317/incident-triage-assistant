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
            "(?im)(?<header>(?<![\\w-])[\"']?(?:authorization|cookie)[\"']?[\\t ]*[:=][\\t ]*)"
            + "|(?<connectionKey>[\"']?\\bconnection[-_]?string[\"']?[\\t ]*[:=][\\t ]*)"
            + "|(?<key>[\"']?\\b(?:api[-_]?key|password|passwd|pwd)[\"']?[\\t ]*[:=][\\t ]*)"
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
        int cursor = 0;
        while (matcher.find(cursor)) {
            result.append(text, cursor, matcher.start());
            String prefix = "";
            String secret = matcher.group();
            int end = matcher.end();
            if (matcher.group("header") != null || matcher.group("key") != null
                    || matcher.group("connectionKey") != null) {
                prefix = matcher.group();
                end = valueEnd(text, end, matcher.group("key") == null);
                secret = text.substring(matcher.end(), end);
            }
            String quote = "";
            if (secret.length() >= 2 && (secret.startsWith("\"") || secret.startsWith("'"))
                    && secret.charAt(secret.length() - 1) == secret.charAt(0)) {
                quote = secret.substring(0, 1);
                secret = secret.substring(1, secret.length() - 1);
            }
            String alias = aliases.computeIfAbsent(secret, ignored -> "[MASKED_" + (aliases.size() + 1) + "]");
            result.append(prefix).append(quote).append(alias).append(quote);
            cursor = end;
        }
        result.append(text, cursor, text.length());
        return result.toString();
    }

    private int valueEnd(String text, int start, boolean compound) {
        if (start == text.length()) return start;
        char quote = text.charAt(start);
        boolean quoted = quote == '"' || quote == '\'';
        for (int i = quoted ? start + 1 : start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n') return i;
            if (quoted) {
                // Escaped quotes are data. A stray internal quote followed by more value
                // characters is data too (e.g. password="abc"DEF-secret").
                if (c == '\\' && i + 1 < text.length()
                        && text.charAt(i + 1) != '\r' && text.charAt(i + 1) != '\n') {
                    i++;
                } else if (c == quote && (i + 1 == text.length() || boundary(text.charAt(i + 1)))) {
                    return i + 1;
                }
            } else if (c == ',' || c == '}' || c == ']' || (!compound && (Character.isWhitespace(c) || c == ';'))) {
                return i;
            }
        }
        return text.length();
    }

    private boolean boundary(char c) {
        return Character.isWhitespace(c) || c == ',' || c == ';' || c == '}' || c == ']';
    }
}
