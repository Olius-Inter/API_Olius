package br.com.olius.olius_core_api.service;
import br.com.olius.olius_core_api.dto.IdentityResponses.*;
import br.com.olius.olius_core_api.entity.*;
import br.com.olius.olius_core_api.entity.enums.UserType;
import br.com.olius.olius_core_api.exception.ResourceNotFoundException;
import br.com.olius.olius_core_api.repository.*;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class ProfileService {
    private final UserRepository users;
    private final CitizenRepository citizens;
    private final EstablishmentRepository establishments;
    private final TelephoneRepository telephones;
    public ProfileService(UserRepository users,CitizenRepository citizens,
                          EstablishmentRepository establishments,TelephoneRepository telephones) {
        this.users=users; this.citizens=citizens; this.establishments=establishments; this.telephones=telephones;
    }
    @Transactional(readOnly=true)
    public Profile get(UUID target, UUID actor, UserType role) {
        if(!target.equals(actor) && role!=UserType.ADMIN) throw new AccessDeniedException("Titularidade inválida");
        User u=users.findById(target).orElseThrow(ResourceNotFoundException::new);
        String cpf=null;
        String cnpj=null;
        String qr=null;
        String description=null;
        UUID type=null;
        AddressView address=null;
        if(u.getUserType()==UserType.CITIZENS) {
            Citizen c=citizens.findById(target).orElseThrow(IllegalStateException::new);
            cpf=c.getCpf(); qr=c.getQrToken();
        } else if(u.getUserType()==UserType.ESTABLISHMENT) {
            Establishment e=establishments.findById(target).orElseThrow(IllegalStateException::new);
            cnpj=e.getCnpj(); qr=e.getQrToken(); description=e.getDescription(); type=e.getTypeId();
            Address a=e.getAddress();
            address=new AddressView(a.getId(),a.getState(),a.getCity(),a.getNeighborhood(),a.getStreet(),
                a.getNumber(),a.getCep(),a.getComplement(),a.getLatitude(),a.getLongitude());
        }
        return new Profile(u.getId(),u.getName(),u.getEmail(),u.getUserType(),u.getStatus(),u.getCreatedAt(),
            telephones.findByUserIdOrderByNumber(target).stream().map(Telephone::getNumber).toList(),
            cpf,cnpj,qr,type,description,address);
    }
}
