package com.liyulottery.telegram;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

class TelegramApiClientTest {
    private static final String TEST_TOKEN = "unit-test-token";

    @Test
    void telegramEnabledContextCanConstructTheClient() {
        new ApplicationContextRunner()
                .withUserConfiguration(TelegramClientTestConfiguration.class)
                .withPropertyValues("app.telegram.enabled=true", "app.telegram.token=" + TEST_TOKEN)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(TelegramApiClient.class));
                });
    }

    @Test
    void getUpdatesSendsLongPollingParametersAndReturnsUpdates() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TelegramApiClient client = new TelegramApiClient(properties(), builder);
        server.expect(requestTo("https://api.telegram.org/bot" + TEST_TOKEN + "/getUpdates"))
                .andExpect(method(POST))
                .andExpect(content().json("""
                        {"offset":12,"timeout":20,"allowed_updates":["message"]}
                        """))
                .andRespond(withSuccess("""
                        {"ok":true,"result":[{"update_id":13}]}
                        """, MediaType.APPLICATION_JSON));

        List<Map<String, Object>> updates = client.getUpdates(12, 20);

        assertEquals(1, updates.size());
        assertEquals(13, ((Number) updates.getFirst().get("update_id")).longValue());
        server.verify();
    }

    @Test
    void getUpdatesRejectsAnUnsuccessfulTelegramResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TelegramApiClient client = new TelegramApiClient(properties(), builder);
        server.expect(requestTo("https://api.telegram.org/bot" + TEST_TOKEN + "/getUpdates"))
                .andRespond(withSuccess("""
                        {"ok":false,"description":"poll failed"}
                        """, MediaType.APPLICATION_JSON));

        assertThrows(IllegalStateException.class, () -> client.getUpdates(0, 20));
        server.verify();
    }

    private TelegramProperties properties() {
        return new TelegramProperties(true, TEST_TOKEN, "test_bot", null, null, 20);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TelegramProperties.class)
    @Import(TelegramApiClient.class)
    static class TelegramClientTestConfiguration {
    }
}
