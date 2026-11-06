package br.com.olius.olius_core_api.dto;

import br.com.olius.olius_core_api.validation.ValidProfileUpdate;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** PATCH com presença explícita: omitir um campo não equivale a enviá-lo nulo. */
@ValidProfileUpdate
public final class ProfileUpdate {
    @Size(max=150) private String name;
    private List<@NotBlank @Size(min=10,max=15) String> telephones;
    private boolean namePresent;
    private boolean telephonesPresent;

    @JsonSetter("name") public void setName(String name) { this.name=name; namePresent=true; }
    @JsonSetter("telephones") public void setTelephones(List<String> telephones) {
        this.telephones=telephones; telephonesPresent=true;
    }
    public String getName() { return name; }
    public List<String> getTelephones() { return telephones; }
    public boolean hasName() { return namePresent; }
    public boolean hasTelephones() { return telephonesPresent; }
    @Override public String toString() { return "ProfileUpdate[redacted]"; }
}
