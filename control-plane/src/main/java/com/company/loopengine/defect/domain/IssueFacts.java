package com.company.loopengine.defect.domain;

import java.util.List;

public record IssueFacts(String projectKey, String module, String steps,
                         String expected, String actual, List<String> imageUrls) {}
