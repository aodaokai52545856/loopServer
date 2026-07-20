package com.company.loopengine.observability;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.regex.Pattern;

/**
 * Redacts known token prefixes and values of secret-like keys from log messages.
 */
public class SecretRedactingConverter extends ClassicConverter {

    private static final Pattern TOKEN_PREFIX = Pattern.compile(
            "(?i)\\b(?:(?:glpat-|gldt-|glrt-|gloas-|glcbt-)[A-Za-z0-9_-]+"
                    + "|sk-[A-Za-z0-9_-]{8,}|ghp_[A-Za-z0-9]{20,})");

    private static final Pattern SECRET_KEY_VALUE = Pattern.compile(
            "(?i)([\\\"']?(?:[A-Za-z0-9_.-]*(?:token|secret|password|api[_.?-]?key|private[_.?-]?key)"
                    + ")[\\\"']?\\s*[:=]\\s*)([\\\"']?)([^\\s,;\\\"'}]+)\\2");

    private static final Pattern OPENAI_ENV = Pattern.compile("(?i)\\bOPENAI_API_KEY\\b\\s*=\\s*\\S+");

    public static String redact(String input) {
        if (input == null || input.isBlank()) {
            return input == null ? "" : input;
        }
        String redacted = SECRET_KEY_VALUE.matcher(input).replaceAll("$1$2***$2");
        redacted = OPENAI_ENV.matcher(redacted).replaceAll("OPENAI_API_KEY=***");
        redacted = TOKEN_PREFIX.matcher(redacted).replaceAll("***");
        return redacted;
    }

    @Override
    public String convert(ILoggingEvent event) {
        return redact(event.getFormattedMessage());
    }
}
