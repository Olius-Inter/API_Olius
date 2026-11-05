package br.com.olius.olius_core_api.config;

import br.com.olius.olius_core_api.integration.ia.IaClient;
import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableConfigurationProperties(IaProperties.class)
public class IaConfiguration {
    @Bean
    public IaClient iaClient(IaProperties properties, ObjectMapper mapper) {
        var http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return new IaClient(properties, http, mapper);
    }
}
