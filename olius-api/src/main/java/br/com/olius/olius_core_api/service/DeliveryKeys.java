package br.com.olius.olius_core_api.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/** Vincula a chave enviada à identidade autenticada antes de acessar a chave global do banco. */
public final class DeliveryKeys {
    private static final byte[] DOMAIN="olius:b2c:pev-delivery:idempotency:v1\0"
        .getBytes(StandardCharsets.US_ASCII);
    private DeliveryKeys() { }

    public static UUID internal(UUID validator,UUID clientKey) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            digest.update(DOMAIN);
            digest.update(bytes(validator));
            digest.update(bytes(clientKey));
            byte[] hash=digest.digest();
            // UUID versão 8: valor determinístico definido pela aplicação.
            hash[6]=(byte)((hash[6]&0x0f)|0x80);
            hash[8]=(byte)((hash[8]&0x3f)|0x80);
            var value=ByteBuffer.wrap(hash);
            return new UUID(value.getLong(),value.getLong());
        } catch(NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 indisponível",failure);
        }
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
            .putLong(value.getLeastSignificantBits()).array();
    }
}
