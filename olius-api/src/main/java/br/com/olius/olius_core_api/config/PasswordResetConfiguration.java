package br.com.olius.olius_core_api.config;

import br.com.olius.olius_core_api.service.PasswordResetDelivery;
import br.com.olius.olius_core_api.service.SmtpPasswordResetDelivery;
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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(PasswordResetProperties.class)
public class PasswordResetConfiguration {
    @Bean
    @ConditionalOnProperty(prefix="olius.password-reset",name="enabled",havingValue="true")
    PasswordResetDelivery passwordResetDelivery(JavaMailSender sender,PasswordResetProperties properties,Environment environment) {
        require(environment.getProperty("spring.mail.username"));
        require(environment.getProperty("spring.mail.password"));
        require(properties.from()); require(properties.url());
        if(properties.ttl()==null || properties.ttl().isNegative() || properties.ttl().isZero())
            throw new IllegalStateException("Prazo de recuperação deve ser positivo");
        try {
            var from=new InternetAddress(properties.from(),true);
            from.validate();
            if(properties.from().contains("\r") || properties.from().contains("\n") || from.getPersonal()!=null)
                throw new AddressException();
        } catch(AddressException invalid) { throw new IllegalStateException("Remetente de recuperação inválido"); }
        URI page;
        try { page=URI.create(properties.url()); }
        catch(IllegalArgumentException invalid) { throw new IllegalStateException("URL de recuperação inválida"); }
        boolean local="http".equalsIgnoreCase(page.getScheme())
                && Set.of("localhost","127.0.0.1","[::1]","::1").contains(page.getHost()==null?"":page.getHost())
                && !environment.matchesProfiles("prod");
        if((!"https".equalsIgnoreCase(page.getScheme()) && !local) || page.getHost()==null
                || page.getRawUserInfo()!=null || page.getRawQuery()!=null || page.getRawFragment()!=null)
            throw new IllegalStateException("URL de recuperação exige HTTPS; HTTP somente em loopback fora de prod, sem credenciais, query ou fragmento");
        return new SmtpPasswordResetDelivery(sender,properties.from(),page);
    }

    @Bean(name="passwordResetExecutor")
    @ConditionalOnProperty(prefix="olius.password-reset",name="enabled",havingValue="true")
    ThreadPoolTaskExecutor passwordResetExecutor() {
        var executor=new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2); executor.setMaxPoolSize(2); executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("password-reset-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }

    private static void require(String value) {
        if(!StringUtils.hasText(value)) throw new IllegalStateException("Configuração obrigatória de recuperação ausente");
    }
}
