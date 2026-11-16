package dev.yujian.mergeguard.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HmacTest {

    private static final String SECRET = "test-secret";
    private static final String PAYLOAD = "{\"action\":\"opened\"}";

    @Test
    void signatureIsStable() {
        assertThat(Hmac.sign(SECRET, PAYLOAD)).startsWith("sha256=");
        assertThat(Hmac.sign(SECRET, PAYLOAD)).isEqualTo(Hmac.sign(SECRET, PAYLOAD));
    }

    @Test
    void verifiesCorrectSignature() {
        assertThat(Hmac.verify(SECRET, PAYLOAD, Hmac.sign(SECRET, PAYLOAD))).isTrue();
    }

    @Test
    void rejectsTamperedPayload() {
        String signature = Hmac.sign(SECRET, PAYLOAD);
        assertThat(Hmac.verify(SECRET, PAYLOAD + " ", signature)).isFalse();
    }

    @Test
    void rejectsMissingSecretOrSignature() {
        assertThat(Hmac.verify("", PAYLOAD, Hmac.sign(SECRET, PAYLOAD))).isFalse();
        assertThat(Hmac.verify(SECRET, PAYLOAD, null)).isFalse();
    }
}
