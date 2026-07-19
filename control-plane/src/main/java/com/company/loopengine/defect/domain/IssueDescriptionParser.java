package com.company.loopengine.defect.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class IssueDescriptionParser {
    private static final LinkedHashMap<String, String> HEADINGS = new LinkedHashMap<>();

    static {
        HEADINGS.put("projectKey", "项目标识");
        HEADINGS.put("module", "模块");
        HEADINGS.put("steps", "复现步骤");
        HEADINGS.put("expected", "期望结果");
        HEADINGS.put("actual", "实际结果");
    }

    public CompletenessResult parse(String markdown) {
        Map<String, String> values = new LinkedHashMap<>();
        for (var entry : HEADINGS.entrySet()) {
            values.put(entry.getKey(), section(markdown, entry.getValue()));
        }
        List<String> missing = HEADINGS.keySet().stream()
            .filter(k -> values.get(k).isBlank())
            .toList();
        List<String> images = Pattern.compile("!\\[[^]]*]\\(([^)]+)\\)")
            .matcher(markdown == null ? "" : markdown)
            .results()
            .map(m -> m.group(1))
            .toList();
        return new CompletenessResult(
            new IssueFacts(
                values.get("projectKey"),
                values.get("module"),
                values.get("steps"),
                values.get("expected"),
                values.get("actual"),
                images),
            missing);
    }

    private String section(String markdown, String heading) {
        Matcher m = Pattern.compile(
                "(?ms)^##\\s+" + Pattern.quote(heading) + "\\s*$\\R(.*?)(?=^##\\s|\\z)")
            .matcher(markdown == null ? "" : markdown);
        return m.find() ? m.group(1).trim() : "";
    }
}
