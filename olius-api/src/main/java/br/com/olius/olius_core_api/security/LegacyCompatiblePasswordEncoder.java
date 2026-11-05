package br.com.olius.olius_core_api.security;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Lê somente os dois formatos conhecidos; toda escrita continua em Argon2id. */
public final class LegacyCompatiblePasswordEncoder implements PasswordEncoder {
    private static final String LEGACY_PREFIX = "pbkdf2$120000$";
    private static final Pattern CURRENT_HASH_PATTERN = Pattern.compile(
        "\\$argon2id\\$v=19\\$m=19456,t=2,p=1\\$[A-Za-z0-9+/]{22}\\$[A-Za-z0-9+/]{43}");
    private final PasswordEncoder currentEncoder;

    public LegacyCompatiblePasswordEncoder(PasswordEncoder currentEncoder) { this.currentEncoder = currentEncoder; }

    /** Gera somente o formato atual, inclusive para cadastro e redefinição de senha. */
    @Override public String encode(CharSequence rawPassword) { return currentEncoder.encode(rawPassword); }

    /** Rejeita formatos desconhecidos antes do cálculo e preserva exatamente a senha recebida. */
    @Override public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null || encodedPassword.length() > 255) return false;
        if (encodedPassword.startsWith(LEGACY_PREFIX)) return matchesLegacy(rawPassword, encodedPassword);
        if (!isCurrent(encodedPassword)) return false;
        return currentEncoder.matches(rawPassword, encodedPassword);
    }

    /** Indica formato legado válido; o chamador deve verificar a senha antes de substituir o hash. */
    @Override public boolean upgradeEncoding(String encodedPassword) {
        return legacyParts(encodedPassword).isPresent();
    }

    /** Replica SenhaUtil com custo fixo, comparação constante e limpeza dos buffers da senha derivada. */
    private boolean matchesLegacy(CharSequence rawPassword, String encodedPassword) {
        Optional<LegacyHash> parsed = legacyParts(encodedPassword);
        if (parsed.isEmpty()) return false;
        LegacyHash parts = parsed.get();
        char[] password = rawPassword.toString().toCharArray();
        var spec = new PBEKeySpec(password, parts.salt(), 120000, 256);
        Arrays.fill(password, '\0');
        byte[] derived = null;
        try {
            // Mesmo tratamento de caracteres de SenhaUtil: toCharArray, sem trim/normalização.
            derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return MessageDigest.isEqual(parts.hash(), derived);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Algoritmo de verificação de senha indisponível", exception);
        } finally {
            spec.clearPassword();
            if (derived != null) Arrays.fill(derived, (byte) 0);
        }
    }

    /** Representa falha de parsing por ausência, sem permitir parâmetros fora do contrato legado. */
    private static Optional<LegacyHash> legacyParts(String encoded) {
        if (encoded == null || encoded.length() != 83 || !encoded.startsWith(LEGACY_PREFIX)) return Optional.empty();
        String[] parts = encoded.split("\\$", -1);
        if (parts.length != 4) return Optional.empty();
        return canonicalBase64(parts[2], 16, true).flatMap(salt ->
            canonicalBase64(parts[3], 32, true).map(hash -> new LegacyHash(salt, hash)));
    }

    /** Confere parâmetros e Base64 do Argon2id conhecido antes de delegar ao encoder atual. */
    private static boolean isCurrent(String encoded) {
        if (!CURRENT_HASH_PATTERN.matcher(encoded).matches()) return false;
        String[] parts = encoded.split("\\$", -1);
        return canonicalBase64(parts[4], 16, false).isPresent()
            && canonicalBase64(parts[5], 32, false).isPresent();
    }

    /** Decodifica somente Base64 canônico com o tamanho e o padding exigidos pelo formato. */
    private static Optional<byte[]> canonicalBase64(String value, int length, boolean padded) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            var encoder = padded ? Base64.getEncoder() : Base64.getEncoder().withoutPadding();
            if (decoded.length != length || !encoder.encodeToString(decoded).equals(value)) return Optional.empty();
            return Optional.of(decoded);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static final class LegacyHash {
        private final byte[] salt;
        private final byte[] hash;
        private LegacyHash(byte[] salt, byte[] hash) { this.salt = salt; this.hash = hash; }
        private byte[] salt() { return salt; }
        private byte[] hash() { return hash; }
        @Override public String toString() { return "LegacyHash[redacted]"; }
    }
}
