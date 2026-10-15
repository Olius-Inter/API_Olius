package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.entity.UserStatus;
import br.com.olius.olius_core_api.exception.BusinessRuleException;
import br.com.olius.olius_core_api.service.UserAdministrationService;
import br.com.olius.olius_core_api.testfixture.OfficialPostgres;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(properties={"spring.config.import=","olius.security.ephemeral-keys=true"})
class AdminAvailabilityIntegrationTest {
    @Container static final OfficialPostgres POSTGRES=new OfficialPostgres();
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username",() -> OfficialPostgres.API_USER);
        properties.add("spring.datasource.password",() -> OfficialPostgres.API_PASSWORD);
    }
    @Autowired UserAdministrationService service;
    @Test void lastAdminRemainsActiveIncludingConcurrentSelfDeactivation() throws Exception {
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));
        UUID first=administrator(jdbc);
        assertThat(deactivate(first,null)).isFalse();
        assertThat(activeCount(jdbc)).isEqualTo(1);
        UUID second=administrator(jdbc);
        assertThat(deactivate(first,null)).isTrue();
        assertThat(activeCount(jdbc)).isEqualTo(1);
        authenticate(second);
        try { service.status(first,UserStatus.ACTIVE,second); }
        finally { SecurityContextHolder.clearContext(); }
        var ready=new CountDownLatch(2); var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            Future<Boolean> one=executor.submit(() -> { ready.countDown(); start.await(); return deactivate(first,null); });
            Future<Boolean> two=executor.submit(() -> { ready.countDown(); start.await(); return deactivate(second,null); });
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(List.of(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        } finally { start.countDown(); }
        assertThat(activeCount(jdbc)).isEqualTo(1);
    }
    private boolean deactivate(UUID actor,UUID target) {
        authenticate(actor);
        try { service.status(target==null ? actor : target,UserStatus.INACTIVE,actor); return true; }
        catch(BusinessRuleException expected) { return false; }
        finally { SecurityContextHolder.clearContext(); }
    }
    private void authenticate(UUID actor) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            actor,null,List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }
    private UUID administrator(JdbcTemplate jdbc) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,name,email,password_hash,user_type) VALUES (?,'Admin',?,'not-used-for-login','ADMIN')",id,id+"@test.invalid");
        return id;
    }
    private Long activeCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT count(*) FROM users WHERE user_type='ADMIN' AND status='ACTIVE'",Long.class);
    }
}
