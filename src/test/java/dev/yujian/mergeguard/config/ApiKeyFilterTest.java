package dev.yujian.mergeguard.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiKeyFilterTest {

    private ApiKeyFilter filter(String key) {
        MergeGuardProperties properties = new MergeGuardProperties();
        properties.setApiKey(key);
        return new ApiKeyFilter(properties, new ObjectMapper());
    }

    @Test
    void rejectsMissingKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/overview");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter("expected").doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("invalid_api_key");
    }

    @Test
    void acceptsCorrectKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/overview");
        request.addHeader("X-API-Key", "expected");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter("expected").doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void webhookBypassesApiKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/webhooks/github");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter("expected").doFilter(request, response, chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void failsClosedWhenKeyNotConfigured() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/overview");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter("").doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("api_key_not_configured");
    }
}
