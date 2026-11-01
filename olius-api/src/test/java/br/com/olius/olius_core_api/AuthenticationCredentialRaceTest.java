package br.com.olius.olius_core_api;

import br.com.olius.olius_core_api.config.SecurityProperties;
import br.com.olius.olius_core_api.dto.IdentityRequests;
import br.com.olius.olius_core_api.entity.User;
import br.com.olius.olius_core_api.entity.enums.UserStatus;
import br.com.olius.olius_core_api.repository.AuthSessionRepository.Outcome;
import br.com.olius.olius_core_api.repository.UserRepository;
import br.com.olius.olius_core_api.security.SecretTokens;
import br.com.olius.olius_core_api.service.AuthenticationService;
import br.com.olius.olius_core_api.service.SessionTransactions;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthenticationCredentialRaceTest {
    @Test void passesExactlyValidatedHashAndNeverIssuesJwtWhenCredentialChanged() {
        var users=mock(UserRepository.class); var passwords=mock(PasswordEncoder.class);
        var sessions=mock(SessionTransactions.class); var encoder=mock(JwtEncoder.class); var user=mock(User.class);
        var id=UUID.randomUUID();
        when(users.findByEmail("case@example.invalid")).thenReturn(Optional.of(user));
        when(user.getId()).thenReturn(id); when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(user.getPasswordHash()).thenReturn("old-hash","new-hash");
        when(passwords.matches("old-password","old-hash")).thenReturn(true);
        when(sessions.open(eq(id),any(byte[].class),eq("old-hash"))).thenReturn(new Outcome("CREDENTIAL_CHANGED",null,id,null));
        var service=new AuthenticationService(users,passwords,sessions,new SecretTokens(),encoder,new SecurityProperties());
        var request=new IdentityRequests.Login("case@example.invalid","old-password");
        assertThatThrownBy(() -> service.login(request)).isInstanceOf(BadCredentialsException.class);
        verify(user,times(1)).getPasswordHash(); verify(sessions).open(eq(id),any(byte[].class),eq("old-hash"));
        verifyNoInteractions(encoder);
    }
}
