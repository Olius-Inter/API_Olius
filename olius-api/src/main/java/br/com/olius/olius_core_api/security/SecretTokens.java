package br.com.olius.olius_core_api.security;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;
@Component
public class SecretTokens {
    private final SecureRandom random=new SecureRandom();
    public String generate() { byte[] bytes=new byte[32]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    public byte[] hash(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)); }
        catch(NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 indisponível",failure); }
    }
    public boolean valid(String value) { return value!=null && value.matches("[A-Za-z0-9_-]{43}"); }
}
