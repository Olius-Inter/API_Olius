package br.com.olius.olius_core_api.config;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.*;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;

@Configuration @EnableConfigurationProperties(SecurityProperties.class)
public class CryptographyConfiguration {
    private static final Logger LOG=LoggerFactory.getLogger(CryptographyConfiguration.class);
    @Bean public PasswordEncoder passwordEncoder() {
        // Argon2id
        return new Argon2PasswordEncoder(16,32,1,19456,2);
    }
    @Bean public KeyPair jwtKeys(SecurityProperties properties) throws GeneralSecurityException, IOException {
        if(properties.isEphemeralKeys()) {
            LOG.warn("Chaves JWT efêmeras habilitadas: reiniciar invalida tokens. Uso exclusivo local/teste.");
            KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
            return generator.generateKeyPair();
        }
        if(properties.getPrivateKeyPath()==null || properties.getPrivateKeyPath().isBlank()
            || properties.getPublicKeyPath()==null || properties.getPublicKeyPath().isBlank())
            throw new IllegalStateException("Configure os caminhos das chaves RSA; modo efêmero exige opção explícita");
        KeyFactory factory=KeyFactory.getInstance("RSA");
        var privateKey=(RSAPrivateKey)factory.generatePrivate(new PKCS8EncodedKeySpec(
            pem(properties.getPrivateKeyPath(),"PRIVATE KEY")));
        var publicKey=(RSAPublicKey)factory.generatePublic(new X509EncodedKeySpec(
            pem(properties.getPublicKeyPath(),"PUBLIC KEY")));
        if(publicKey.getModulus().bitLength()<2048 || !publicKey.getModulus().equals(privateKey.getModulus()))
            throw new IllegalStateException("Par de chaves RSA inválido");
        return new KeyPair(publicKey,privateKey);
    }
    private byte[] pem(String path,String kind) throws IOException {
        String text=Files.readString(Path.of(path),StandardCharsets.US_ASCII)
            .replace("-----BEGIN "+kind+"-----","").replace("-----END "+kind+"-----","")
            .replaceAll("\\s","");
        return Base64.getDecoder().decode(text);
    }
    @Bean public JwtEncoder jwtEncoder(KeyPair keys,SecurityProperties properties) {
        RSAKey rsa=new RSAKey.Builder((RSAPublicKey)keys.getPublic()).privateKey(keys.getPrivate())
            .keyID(properties.getKeyId()).build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsa)));
    }
    @Bean public JwtDecoder jwtDecoder(KeyPair keys,SecurityProperties properties) {
        NimbusJwtDecoder decoder=NimbusJwtDecoder.withPublicKey((RSAPublicKey)keys.getPublic())
            .signatureAlgorithm(SignatureAlgorithm.RS256).build();
        OAuth2TokenValidator<Jwt> claims=jwt -> {
            if(jwt.getExpiresAt()==null || jwt.getIssuedAt()==null
                    || !jwt.getAudience().contains(properties.getAudience()))
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token","Claims inválidas",null));
            return OAuth2TokenValidatorResult.success();
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            new JwtTimestampValidator(java.time.Duration.ZERO),new JwtIssuerValidator(properties.getIssuer()),claims));
        return decoder;
    }
}
