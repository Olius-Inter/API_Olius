package br.com.olius.olius_core_api.config;

import br.com.olius.olius_core_api.service.AdminInvitationDelivery;
import br.com.olius.olius_core_api.service.SmtpAdminInvitationDelivery;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.net.URI;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(AdminInvitationEmailProperties.class)
public class AdminInvitationEmailConfiguration {
    @Bean
    @ConditionalOnProperty(prefix="olius.admin-invitations.email",name="enabled",havingValue="true")
    AdminInvitationDelivery smtpAdminInvitationDelivery(JavaMailSender sender,
            AdminInvitationEmailProperties properties,Environment environment) {
        require(environment.getProperty("spring.mail.username"),"spring.mail.username");
        require(environment.getProperty("spring.mail.password"),"spring.mail.password");
        require(properties.from(),"olius.admin-invitations.email.from");
        require(properties.acceptanceUrl(),"olius.admin-invitations.email.acceptance-url");
        try {
            InternetAddress address=new InternetAddress(properties.from(),true);
            address.validate();
            if(properties.from().contains("\r") || properties.from().contains("\n") || address.getPersonal()!=null)
                throw new AddressException();
        } catch(AddressException invalid) {
            throw new IllegalStateException("Remetente de convite inválido");
        }
        URI page;
        try { page=URI.create(properties.acceptanceUrl()); }
        catch(IllegalArgumentException invalid) { throw new IllegalStateException("URL de aceite inválida"); }
        boolean https="https".equalsIgnoreCase(page.getScheme());
        boolean localHttp="http".equalsIgnoreCase(page.getScheme())
                && Set.of("localhost","127.0.0.1","[::1]","::1").contains(page.getHost()==null?"":page.getHost())
                && !environment.matchesProfiles("prod");
        if((!https && !localHttp) || page.getHost()==null || page.getRawUserInfo()!=null
                || page.getRawQuery()!=null || page.getRawFragment()!=null)
            throw new IllegalStateException("URL de aceite deve usar HTTPS; HTTP somente em loopback fora de prod, sem query, fragmento ou credenciais");
        return new SmtpAdminInvitationDelivery(sender,properties.from(),page);
    }

    private static void require(String value,String property) {
        if(!StringUtils.hasText(value)) throw new IllegalStateException("Configuração obrigatória ausente: "+property);
    }
}
