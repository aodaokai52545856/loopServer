package com.company.loopengine.defect.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class IssueDescriptionParserTest {
    private IssueDescriptionParser parser;

    @BeforeEach
    void setUp() {
        parser = new IssueDescriptionParser();
    }

    @ParameterizedTest
    @MethodSource("cases")
    void reportsExactMissingFields(String description, List<String> expected) {
        assertThat(parser.parse(description).missingFields()).containsExactlyElementsOf(expected);
    }

    static Stream<Arguments> cases() {
        return Stream.of(
            arguments(
                """
                ## 项目标识
                backend-a
                ## 模块
                order
                ## 复现步骤
                1. create
                ## 期望结果
                ok
                ## 实际结果
                500
                """,
                List.of()),
            arguments(
                """
                ## 项目标识
                backend-a
                ## 模块

                ## 复现步骤

                """,
                List.of("module", "steps", "expected", "actual")),
            arguments(
                """
                ## 实际结果
                500
                ## 期望结果
                ok
                ## 复现步骤
                1. create
                ## 模块
                order
                ## 项目标识
                backend-a
                """,
                List.of()),
            arguments(
                "## 项目标识\r\nbackend-a\r\n## 模块\r\norder\r\n## 复现步骤\r\n1. create\r\n## 期望结果\r\nok\r\n## 实际结果\r\n500\r\n",
                List.of()));
    }
}
