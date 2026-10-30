package br.com.olius.olius_core_api.service;
import br.com.olius.olius_core_api.dto.IdentityRequests.*;
import br.com.olius.olius_core_api.dto.IdentityResponses.Registration;
import br.com.olius.olius_core_api.entity.*;
import br.com.olius.olius_core_api.entity.enums.UserType;
import br.com.olius.olius_core_api.exception.ResourceNotFoundException;
import br.com.olius.olius_core_api.repository.EstablishmentTypeRepository;
import br.com.olius.olius_core_api.security.SecretTokens;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationService {
    private final EntityManager em;
    private final PasswordEncoder passwords;
    private final AuditContext audit;
    private final EstablishmentTypeRepository types;
    private final SecretTokens tokens;
    public RegistrationService(EntityManager em, PasswordEncoder passwords, AuditContext audit,
                               EstablishmentTypeRepository types, SecretTokens tokens) {
        this.em=em; this.passwords=passwords; this.audit=audit; this.types=types; this.tokens=tokens;
    }
    @Transactional
    public Registration citizen(CitizenRegistration request) {
        UUID id=createUser(request.name(),request.email(),request.password(), UserType.CITIZENS);
        String qr=qr(id,UserType.CITIZENS);
        em.persist(new Citizen(id,request.cpf(),qr));
        phones(id,request.telephones()); em.flush();
        return new Registration(id,UserType.CITIZENS);
    }
    @Transactional
    public Registration establishment(EstablishmentRegistration request) {
        if(!types.existsById(request.typeId())) throw new ResourceNotFoundException();
        UUID id=createUser(request.name(),request.email(),request.password(),UserType.ESTABLISHMENT);
        String qr=qr(id,UserType.ESTABLISHMENT);
        var a=request.address();
        UUID addressId=UUID.randomUUID();
        em.persist(new Address(addressId,
            new Address.PostalDetails(a.state(),a.city(),a.neighborhood(),a.street(),a.number(),a.cep(),a.complement()),
            new Address.Coordinates(a.latitude(),a.longitude()))); em.flush();
        em.persist(new Establishment(id,request.cnpj(),qr,request.description(),request.typeId(),addressId));
        phones(id,request.telephones()); em.flush();
        return new Registration(id,UserType.ESTABLISHMENT);
    }
    private UUID createUser(String name, String email, String password, UserType type) {
        UUID id=UUID.randomUUID(); audit.user(id,"Cadastro público do titular");
        persistUser(id,name,email,password,type); return id;
    }
    private void persistUser(UUID id,String name,String email,String password,UserType type) {
        em.persist(new User(id,name.strip(),EmailAddress.normalize(email),passwords.encode(password),type));
        // O usuário precisa existir antes de registrar QR/especialização e autor da auditoria.
        em.flush();
    }
    private String qr(UUID id,UserType type) {
        String token=tokens.generate(); em.persist(new UserQrCode(id,token,type)); em.flush(); return token;
    }
    private void phones(UUID id,List<String> phones) {
        if(phones!=null) phones.forEach(number -> em.persist(new Telephone(id,number)));
    }
}
