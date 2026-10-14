package br.com.olius.olius_core_api.security;
import br.com.olius.olius_core_api.config.CryptographyConfiguration;
import br.com.olius.olius_core_api.config.SecurityProperties;

import java.nio.file.*;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
class CryptographyConfigurationTest {
    @TempDir Path directory;
    @Test void persistentPemKeysSurviveReloadAndMissingConfigFailsClosed() throws Exception {
        var config=new CryptographyConfiguration(); var ephemeral=new SecurityProperties(); ephemeral.setEphemeralKeys(true);
        var keys=config.jwtKeys(ephemeral); var privatePath=directory.resolve("private.pem"); var publicPath=directory.resolve("public.pem");
        Files.writeString(privatePath,pem("PRIVATE KEY",keys.getPrivate().getEncoded()));
        Files.writeString(publicPath,pem("PUBLIC KEY",keys.getPublic().getEncoded()));
        var persistent=new SecurityProperties(); persistent.setPrivateKeyPath(privatePath.toString()); persistent.setPublicKeyPath(publicPath.toString());
        var loaded=config.jwtKeys(persistent); assertThat(loaded.getPublic().getEncoded()).isEqualTo(keys.getPublic().getEncoded());
        assertThat(loaded.getPrivate().getEncoded()).isEqualTo(keys.getPrivate().getEncoded());
        var missingKeys = new SecurityProperties();
        assertThatThrownBy(() -> config.jwtKeys(missingKeys)).isInstanceOf(IllegalStateException.class);
    }
    private String pem(String kind,byte[] bytes) {
        return "-----BEGIN "+kind+"-----\n"+Base64.getEncoder().encodeToString(bytes)+"\n-----END "+kind+"-----\n";
    }
}
