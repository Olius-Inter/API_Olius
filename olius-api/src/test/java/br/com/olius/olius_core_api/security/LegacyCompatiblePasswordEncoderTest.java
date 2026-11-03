package br.com.olius.olius_core_api.security;

import br.com.olius.olius_core_api.config.CryptographyConfiguration;
import br.com.olius.olius_core_api.testfixture.LegacyPasswords;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class LegacyCompatiblePasswordEncoderTest {
    private final org.springframework.security.crypto.password.PasswordEncoder encoder =
        new CryptographyConfiguration().passwordEncoder();

    // Vetores independentes gerados com Python hashlib.pbkdf2_hmac('sha256', UTF-8, salt 00..0f, 120000, 32).
    static Stream<Arguments> vectors() { return Stream.of(
            Arguments.of("SenhaLegada1!","pbkdf2$120000$AAECAwQFBgcICQoLDA0ODw==$BVqNctp8ex49QVPPw4xcjSjhHnAKW5BmwQ4mNZ3EWoM="),
            Arguments.of("  Ação🔑東京  ","pbkdf2$120000$AAECAwQFBgcICQoLDA0ODw==$k9pknV8yLk0dZkKGG6l4YdOAUJpu3uMClq36xpptMbQ="),
            Arguments.of("é","pbkdf2$120000$AAECAwQFBgcICQoLDA0ODw==$A/qQnsHXDIisSpax8BsVS5ZjHf/7HWpTHvfBpodqDmo=")); }

    @ParameterizedTest @MethodSource("vectors")
    void matchesOriginalJavaAndIndependentVectorsWithoutNormalizing(String password, String hash) {
        assertThat(LegacyPasswords.hash(password)).isEqualTo(hash);
        assertThat(encoder.matches(password,hash)).isTrue();
        assertThat(encoder.matches(password+"x",hash)).isFalse();
        assertThat(encoder.upgradeEncoding(hash)).isTrue();
    }

    @Test void whitespaceAndUnicodeNormalizationAreNotChanged() {
        assertThat(encoder.matches("Ação🔑東京",LegacyPasswords.hash("  Ação🔑東京  "))).isFalse();
        assertThat(encoder.matches("e\u0301",LegacyPasswords.hash("é"))).isFalse();
    }

    @Test void currentWritesAndReadsRemainArgon2WithoutUpgrade() {
        String hash=encoder.encode("CurrentPassword1!");
        assertThat(hash).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
        assertThat(encoder.matches("CurrentPassword1!",hash)).isTrue();
        assertThat(encoder.matches("wrong",hash)).isFalse();
        assertThat(encoder.matches(null,hash)).isFalse();
        assertThat(encoder.upgradeEncoding(hash)).isFalse();
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={
        "plaintext-password", "{noop}plaintext-password", "{pbkdf2}abc", "pbkdf2$120000$x$y",
        "pbkdf2$0$AAECAwQFBgcICQoLDA0ODw==$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "pbkdf2$2147483647$AAECAwQFBgcICQoLDA0ODw==$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "pbkdf2$0120000$AAECAwQFBgcICQoLDA0ODw==$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "pbkdf2$120000$AAECAwQFBgcICQoLDA0ODw==$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=$extra",
        "pbkdf2$120000$!!!!!!!!!!!!!!!!!!!!!!!!$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "$argon2id$v=19$m=999999999,t=2,p=1$AAECAwQFBgcICQoLDA0ODw$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    })
    void rejectsUnsupportedMalformedAndExpensiveFormats(String hash) {
        assertThat(encoder.matches("anything",hash)).isFalse();
        assertThat(encoder.upgradeEncoding(hash)).isFalse();
    }

    @Test void rejectsWrongSizesNonCanonicalBase64AndUnboundedInput() {
        String valid=LegacyPasswords.hash("test");
        assertThat(encoder.matches("test",valid.replace("ODw==", "ODx=="))).isFalse();
        assertThat(encoder.matches("test",valid.replace("ODw==", "ODw"))).isFalse();
        assertThat(encoder.matches("test",valid+"x".repeat(10000))).isFalse();
        assertThat(encoder.matches("test",valid.replace("120000","120001"))).isFalse();
        String current=encoder.encode("test");
        assertThat(encoder.matches("test",current.replace("$v=19$","$v=16$"))).isFalse();
    }
}
