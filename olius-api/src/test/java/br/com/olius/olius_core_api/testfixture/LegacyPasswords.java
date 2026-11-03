package br.com.olius.olius_core_api.testfixture;

import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Algoritmo original SenhaUtil, somente credenciais fictícias de teste. */
public final class LegacyPasswords {
    private LegacyPasswords() { }
    /** Reproduz o PBKDF2 original com salt fictício fixo para testes reproduzíveis, nunca para produção. */
    public static String hash(String password) {
        byte[] salt = new byte[16];
        for (int i=0;i<salt.length;i++) salt[i]=(byte)i;
        var spec = new PBEKeySpec(password.toCharArray(),salt,120000,256);
        try {
            return "pbkdf2$120000$"+Base64.getEncoder().encodeToString(salt)+"$"
                +Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded());
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        } finally { spec.clearPassword(); }
    }
}
