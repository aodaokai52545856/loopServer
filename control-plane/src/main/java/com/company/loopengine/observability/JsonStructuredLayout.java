package com.company.loopengine.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.LayoutBase;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.MDC;

/**
 * Emits one JSON object per log line with stable observability fields and redacted message text.
 */
public class JsonStructuredLayout extends LayoutBase<ILoggingEvent> {

    private String serviceName = "loop-engine-control-plane";

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    @Override
    public String doLayout(ILoggingEvent event) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("timestamp", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        fields.put("level", event.getLevel().toString());
        fields.put("service", serviceName);
        putIfPresent(fields, "requestId", firstNonBlank(event.getMDCPropertyMap().get("requestId"), MDC.get("requestId")));
        putIfPresent(fields, "taskId", firstNonBlank(event.getMDCPropertyMap().get("taskId"), MDC.get("taskId")));
        putIfPresent(fields, "attemptId", firstNonBlank(event.getMDCPropertyMap().get("attemptId"), MDC.get("attemptId")));
        putIfPresent(fields, "nodeId", firstNonBlank(event.getMDCPropertyMap().get("nodeId"), MDC.get("nodeId")));
        putIfPresent(fields, "event", firstNonBlank(event.getMDCPropertyMap().get("event"), MDC.get("event")));
        putIfPresent(fields, "errorCode", firstNonBlank(event.getMDCPropertyMap().get("errorCode"), MDC.get("errorCode")));
        fields.put("message", SecretRedactingConverter.redact(event.getFormattedMessage()));
        fields.put("logger", event.getLoggerName());
        return fields.entrySet().stream()
                        .map(e -> jsonString(e.getKey()) + ":" + jsonString(e.getValue()))
                        .collect(Collectors.joining(",", "{", "}"))
                + System.lineSeparator();
    }

    private static void putIfPresent(Map<String, String> fields, String key, String value) {
        if (value != null && !value.isBlank()) {
            fields.put(key, value);
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }

    private static String jsonString(String value) {
        String escaped = value == null
                ? ""
                : value.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                        .replace("\t", "\\t");
        return "\"" + escaped + "\"";
    }
}
