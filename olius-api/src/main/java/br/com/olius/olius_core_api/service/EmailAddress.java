package br.com.olius.olius_core_api.service;
import java.util.Locale;
/** Preserva maiúsculas da parte local conforme a decisão funcional. */
public final class EmailAddress {
    private EmailAddress() { }
    public static String normalize(String email) {
        String value=email.strip(); int at=value.lastIndexOf('@');
        return at<0 ? value : value.substring(0,at+1)+value.substring(at+1).toLowerCase(Locale.ROOT);
    }
}
