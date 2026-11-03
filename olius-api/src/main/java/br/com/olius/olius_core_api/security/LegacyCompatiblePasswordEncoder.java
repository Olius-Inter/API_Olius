package br.com.olius.olius_core_api.security;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Pattern;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.security.crypto.password.PasswordEncoder;

/** LÃª somente os dois formatos conhecidos; toda escrita continua em Argon2id. */
public final class LegacyCompatiblePasswordEncoder implements PasswordEncoder {
    private static final String LEGACY_PREFIX = "pbkdf2$120000$";
    private static final Pattern CURRENT = Pattern.compile(
        "\\$argon2id\\$v=19\\$m=19456,t=2,p=1\\$[A-Za-z0-9+/]{22}\\$[A-Za-z0-9+/]{43}");
    private final PasswordEncoder current;

    public LegacyCompatiblePasswordEncoder(PasswordEncoder current) { this.current = current; }

    @Override public String encode(CharSequence rawPassword) { return current.encode(rawPassword); }

    @Override public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null || encodedPassword.length() > 255) return false;
        if (encodedPassword.startsWith(LEGACY_PREFIX)) return matchesLegacy(rawPassword, encodedPassword);
        if (!isCurrent(encodedPassword)) return false;
        return current.matches(rawPassword, encodedPassword);
    }

    @Override public boolean upgradeEncoding(String encodedPassword) {
        return legacyParts(encodedPassword) != null;
    }

    private boolean matchesLegacy(CharSequence rawPassword, String encodedPassword) {
        byte[][] parts = legacyParts(encodedPassword);
        if (parts == null) return false;
        char[] password = rawPassword.toString().toCharArray();
        var spec = new PBEKeySpec(password, parts[0], 120000, 256);
        Arrays.fill(password, '\0');
        byte[] derived = null;
        try {
            // Mesmo tratamento de caracteres de SenhaUtil: toCharArray, sem trim/normalizaÃ§Ã£o.
            derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return MessageDigest.isEqual(parts[1], derived);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Algoritmo de verificaÃ§Ã£o de senha indisponÃ­vel", exception);
        } finally {
            spec.clearPassword();
            if (derived != null) Arrays.fill(derived, (byte) 0);
        }
    }

    private static byte[][] legacyParts(String encoded) {
        if (encoded == null || encoded.length() != 83 || !encoded.startsWith(LEGACY_PREFIX)) return null;
        String[] parts = encoded.split("\\$", -1);
        if (parts.length != 4) return null;
        byte[] salt = canonicalBase64(parts[2], 16, true);
        byte[] hash = canonicalBase64(parts[3], 32, true);
        return salt == null || hash == null ? null : new byte[][]{salt, hash};
    }

    private static boolean isCurrent(String encoded) {
        if (!CURRENT.matcher(encoded).matches()) return false;
        String[] parts = encoded.split("\\$", -1);
        return canonicalBase64(parts[4], 16, false) != null && canonicalBase64(parts[5], 32, false) != null;
    }

    private static byte[] canonicalBase64(String value, int length, boolean padded) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            var encoder = padded ? Base64.getEncoder() : Base64.getEncoder().withoutPadding();
            return decoded.length == length && encoder.encodeToString(decoded).equals(value) ? decoded : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
