package com.writing.novel.llm.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-174: LLM 客户端超时可配置化——legacy 默认构造路径的三项超时
 * （connect/read/call）可经系统属性覆盖，未设置时保持既有默认（30s/30min/30min）。
 *
 * <p>背景：1003 验证轮 P1 挂死分析发现 legacy 路径 30 分钟兜底不可配置；
 * 生产 named-provider 路径已按 request-timeout-seconds 生效，本特性为其提供
 * 运维 escape hatch 并收紧 extract 类 provider 预算（application.yml 300s）。
 */
class OpenAiCompatibleClientTimeoutTest {

    private static final String CONNECT_PROP = "novel.llm.connect-timeout-seconds";
    private static final String READ_PROP = "novel.llm.read-timeout-seconds";
    private static final String CALL_PROP = "novel.llm.call-timeout-seconds";

    @BeforeEach
    void clearProps() {
        System.clearProperty(CONNECT_PROP);
        System.clearProperty(READ_PROP);
        System.clearProperty(CALL_PROP);
    }

    @AfterEach
    void clearPropsAgain() {
        clearProps();
    }

    @Test
    void 未设置属性时保持既有默认30分钟兜底() {
        var client = new OpenAiCompatibleClient();
        assertEquals(TimeUnit.SECONDS.toMillis(30), client.httpClient().connectTimeoutMillis());
        assertEquals(TimeUnit.MINUTES.toMillis(30), client.httpClient().readTimeoutMillis());
        assertEquals(TimeUnit.MINUTES.toMillis(30), client.httpClient().callTimeoutMillis());
    }

    @Test
    void 系统属性可覆盖三超时_运维逃逸口() {
        System.setProperty(CONNECT_PROP, "10");
        System.setProperty(READ_PROP, "120");
        System.setProperty(CALL_PROP, "600");
        var client = new OpenAiCompatibleClient();
        assertEquals(TimeUnit.SECONDS.toMillis(10), client.httpClient().connectTimeoutMillis());
        assertEquals(TimeUnit.SECONDS.toMillis(120), client.httpClient().readTimeoutMillis());
        assertEquals(TimeUnit.SECONDS.toMillis(600), client.httpClient().callTimeoutMillis());
    }

    @Test
    void 非法属性值_忽略并回落默认() {
        System.setProperty(READ_PROP, "not-a-number");
        var client = new OpenAiCompatibleClient();
        assertEquals(TimeUnit.MINUTES.toMillis(30), client.httpClient().readTimeoutMillis(),
                "非法值不得导致构造失败，应回落默认");
    }
}
