package com.company.loopengine.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Low-cardinality Loop Engine metrics. Labels are limited to stable enums such as state,
 * project_key, outcome, executor, os and kind — never issue/task/node IDs, URLs or error text.
 */
@Component
public class LoopMetrics {

    public static final List<String> METRIC_NAMES = List.of(
            "loop_defects_current",
            "loop_tasks_current",
            "loop_task_wait_seconds",
            "loop_attempt_duration_seconds",
            "loop_nodes",
            "loop_node_slots",
            "loop_webhook_deliveries_total",
            "loop_outbox_events_total",
            "loop_publish_total");

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, AtomicInteger> gauges = new ConcurrentHashMap<>();

    public LoopMetrics(MeterRegistry registry) {
        this.registry = registry;
        // Seed representative low-cardinality series so scrape families are discoverable.
        setDefectsCurrent("queued", "backend-a", 0);
        setTasksCurrent("queued", "backend-a", 0);
        observeTaskWait("backend-a", 0);
        observeAttemptDuration("succeeded", "opencode", 0);
        setNodes("online", "linux", 0);
        setNodeSlots("active", 0);
        setNodeSlots("limit", 0);
        countWebhookDelivery("accepted");
        countOutboxEvent("pending", "gitlab.label");
        countPublish("succeeded", "mr");
    }

    public List<String> metricNames() {
        return METRIC_NAMES;
    }

    public void setDefectsCurrent(String state, String projectKey, int value) {
        gauge("loop_defects_current", Tags.of("state", state, "project_key", projectKey), value);
    }

    public void setTasksCurrent(String state, String projectKey, int value) {
        gauge("loop_tasks_current", Tags.of("state", state, "project_key", projectKey), value);
    }

    public void observeTaskWait(String projectKey, long seconds) {
        Timer.builder("loop_task_wait_seconds")
                .tags(Tags.of("project_key", projectKey))
                .register(registry)
                .record(java.time.Duration.ofSeconds(Math.max(0, seconds)));
    }

    public void observeAttemptDuration(String outcome, String executor, long seconds) {
        Timer.builder("loop_attempt_duration_seconds")
                .tags(Tags.of("outcome", outcome, "executor", executor))
                .register(registry)
                .record(java.time.Duration.ofSeconds(Math.max(0, seconds)));
    }

    public void setNodes(String state, String os, int value) {
        gauge("loop_nodes", Tags.of("state", state, "os", os), value);
    }

    public void setNodeSlots(String kind, int value) {
        if (!"active".equals(kind) && !"limit".equals(kind)) {
            throw new IllegalArgumentException("kind must be active or limit");
        }
        gauge("loop_node_slots", Tags.of("kind", kind), value);
    }

    public void countWebhookDelivery(String result) {
        Counter.builder("loop_webhook_deliveries_total")
                .tags(Tags.of("result", result))
                .register(registry)
                .increment();
    }

    public void countOutboxEvent(String result, String type) {
        Counter.builder("loop_outbox_events_total")
                .tags(Tags.of("result", result, "type", type))
                .register(registry)
                .increment();
    }

    public void countPublish(String result, String step) {
        Counter.builder("loop_publish_total")
                .tags(Tags.of("result", result, "step", step))
                .register(registry)
                .increment();
    }

    private void gauge(String name, Tags tags, int value) {
        String key = name + tags.toString();
        AtomicInteger holder = gauges.computeIfAbsent(key, ignored -> {
            AtomicInteger created = new AtomicInteger(0);
            Gauge.builder(name, created, AtomicInteger::get).tags(tags).register(registry);
            return created;
        });
        holder.set(value);
    }
}
