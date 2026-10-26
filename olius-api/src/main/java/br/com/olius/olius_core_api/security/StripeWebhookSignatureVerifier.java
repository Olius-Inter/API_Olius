package br.com.olius.olius_core_api.security;

import br.com.olius.olius_core_api.exception.InvalidWebhookException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Protocolo Stripe: HMAC-SHA256(timestamp + ponto + bytes originais). */
public final class StripeWebhookSignatureVerifier {
    private final String secret;
    private final Clock clock;
    public StripeWebhookSignatureVerifier(String secret,Clock clock) {
        if(secret==null || !secret.startsWith("whsec_") || secret.length()<=6)
            throw new IllegalStateException("Configure o segredo de assinatura do webhook Stripe");
        this.secret=secret;this.clock=clock;
    }
    public void verify(byte[] body,String header) {
        if(header==null || header.length()>4096 || body==null) throw new InvalidWebhookException();
        try {
            String timestamp=null;
            for(String part:header.split(",")) {
                String item=part.trim();
                if(item.startsWith("t=")) {
                    if(timestamp!=null) throw new InvalidWebhookException();
                    timestamp=item.substring(2);
                }
            }
            if(timestamp==null) throw new InvalidWebhookException();
            Instant signedAt=Instant.ofEpochSecond(Long.parseLong(timestamp));
            Instant now=clock.instant();
            if(signedAt.isBefore(now.minusSeconds(300)) || signedAt.isAfter(now.plusSeconds(300)))
                throw new InvalidWebhookException();
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            mac.update((timestamp+".").getBytes(StandardCharsets.UTF_8));
            byte[] expected=mac.doFinal(body);
            boolean matched=false;
            for(String part:header.split(",")) {
                String item=part.trim();
                if(item.startsWith("v1=") && item.substring(3).matches("[0-9a-fA-F]{64}"))
                    matched=MessageDigest.isEqual(expected,HexFormat.of().parseHex(item.substring(3))) || matched;
            }
            if(!matched) throw new InvalidWebhookException();
        } catch(NumberFormatException | DateTimeException ex) {
            throw new InvalidWebhookException();
        } catch(GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC indisponível");
        }
    }
}
