package br.com.olius.olius_core_api.config;
import br.com.olius.olius_core_api.dto.IdentityRequests.AdminRegistration;
import br.com.olius.olius_core_api.service.FirstAdminService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
@Component @ConditionalOnProperty(name="olius.admin-bootstrap.enabled",havingValue="true")
public class FirstAdminRunner implements ApplicationRunner {
    private static final Logger LOG=LoggerFactory.getLogger(FirstAdminRunner.class);
    private final FirstAdminService service; private final Environment env; private final ConfigurableApplicationContext context;
    public FirstAdminRunner(FirstAdminService service,Environment env,ConfigurableApplicationContext context) {
        this.service=service; this.env=env; this.context=context;
    }
    @Override public void run(ApplicationArguments args) {
        if(!"none".equals(env.getProperty("spring.main.web-application-type")))
            throw new IllegalStateException("Provisionamento exige execução offline com web-application-type=none");
        service.provision(new AdminRegistration(env.getRequiredProperty("OLIUS_INITIAL_ADMIN_NAME"),
            env.getRequiredProperty("OLIUS_INITIAL_ADMIN_EMAIL"),env.getRequiredProperty("OLIUS_INITIAL_ADMIN_PASSWORD")));
        LOG.info("Administrador inicial provisionado. Execução offline concluída.");
        context.close();
    }
}
