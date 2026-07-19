package com.company.loopengine.gitlab.webhook;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("loop.gitlab.webhook")
public record GitLabWebhookProperties(String token) {}
