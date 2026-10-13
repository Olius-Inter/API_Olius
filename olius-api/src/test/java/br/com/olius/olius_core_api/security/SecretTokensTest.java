package br.com.olius.olius_core_api.security;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class SecretTokensTest {
    @Test void opaqueTokensAreRandomAndOnlySha256IsPersistable() throws Exception {
        var tokens=new SecretTokens(); String first=tokens.generate(); String second=tokens.generate();
        assertThat(first).hasSize(43).isNotEqualTo(second); assertThat(tokens.valid(first)).isTrue();
        assertThat(tokens.hash(first)).hasSize(32).isEqualTo(MessageDigest.getInstance("SHA-256").digest(first.getBytes(StandardCharsets.US_ASCII)));
        assertThat(tokens.valid(null)).isFalse(); assertThat(tokens.valid("bad")).isFalse();
    }
}
