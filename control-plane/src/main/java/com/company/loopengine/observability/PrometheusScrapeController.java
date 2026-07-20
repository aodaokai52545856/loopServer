package com.company.loopengine.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal Prometheus text exposition for MeterRegistry without requiring an extra registry
 * dependency. Label sets stay low-cardinality; secret-looking label values are redacted.
 */
@RestController
public class PrometheusScrapeController {

    private final MeterRegistry registry;

    public PrometheusScrapeController(MeterRegistry registry) {
        this.registry = registry;
    }

    @GetMapping(path = "/actuator/prometheus", produces = MediaType.TEXT_PLAIN_VALUE)
    public String scrape() {
        StringBuilder out = new StringBuilder();
        Map<String, List<Meter>> byName = new LinkedHashMap<>();
        for (Meter meter : registry.getMeters()) {
            byName.computeIfAbsent(meter.getId().getName(), ignored -> new ArrayList<>()).add(meter);
        }
        byName.keySet().stream().sorted().forEach(name -> writeFamily(out, name, byName.get(name)));
        return out.toString();
    }

    private void writeFamily(StringBuilder out, String name, List<Meter> meters) {
        String type = prometheusType(meters.getFirst());
        out.append("# HELP ").append(name).append(' ').append(name).append('\n');
        out.append("# TYPE ").append(name).append(' ').append(type).append('\n');
        for (Meter meter : meters) {
            writeMeter(out, meter);
        }
    }

    private void writeMeter(StringBuilder out, Meter meter) {
        String name = meter.getId().getName();
        String labels = formatLabels(meter.getId().getTags());
        switch (meter.getId().getType()) {
            case GAUGE -> out.append(name)
                    .append(labels)
                    .append(' ')
                    .append(format(((Gauge) meter).value()))
                    .append('\n');
            case COUNTER -> {
                double value = meter instanceof Counter counter
                        ? counter.count()
                        : ((FunctionCounter) meter).count();
                out.append(name).append(labels).append(' ').append(format(value)).append('\n');
            }
            case TIMER -> {
                Timer timer = (Timer) meter;
                out.append(name)
                        .append("_count")
                        .append(labels)
                        .append(' ')
                        .append(format(timer.count()))
                        .append('\n');
                out.append(name)
                        .append("_sum")
                        .append(labels)
                        .append(' ')
                        .append(format(timer.totalTime(TimeUnit.SECONDS)))
                        .append('\n');
            }
            case DISTRIBUTION_SUMMARY -> {
                DistributionSummary summary = (DistributionSummary) meter;
                out.append(name)
                        .append("_count")
                        .append(labels)
                        .append(' ')
                        .append(format(summary.count()))
                        .append('\n');
                out.append(name)
                        .append("_sum")
                        .append(labels)
                        .append(' ')
                        .append(format(summary.totalAmount()))
                        .append('\n');
            }
            case LONG_TASK_TIMER -> {
                LongTaskTimer timer = (LongTaskTimer) meter;
                out.append(name)
                        .append("_active_count")
                        .append(labels)
                        .append(' ')
                        .append(format(timer.activeTasks()))
                        .append('\n');
            }
            default -> {
                // ignore unsupported meter types
            }
        }
    }

    private static String prometheusType(Meter meter) {
        return switch (meter.getId().getType()) {
            case COUNTER -> "counter";
            case GAUGE -> "gauge";
            case TIMER, DISTRIBUTION_SUMMARY, LONG_TASK_TIMER -> "summary";
            default -> "untyped";
        };
    }

    private static String formatLabels(Iterable<Tag> tags) {
        List<Tag> sorted = StreamSupport.stream(tags.spliterator(), false)
                .sorted(Comparator.comparing(Tag::getKey))
                .toList();
        if (sorted.isEmpty()) {
            return "";
        }
        return sorted.stream()
                .map(tag -> tag.getKey() + "=\"" + escape(SecretRedactingConverter.redact(tag.getValue())) + "\"")
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"");
    }

    private static String format(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (value == Double.POSITIVE_INFINITY) {
            return "+Inf";
        }
        if (value == Double.NEGATIVE_INFINITY) {
            return "-Inf";
        }
        return String.format(Locale.ROOT, "%s", value);
    }
}
