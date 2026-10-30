package br.com.olius.olius_core_api.dto;

import br.com.olius.olius_core_api.entity.enums.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;

public final class DriverContracts {
    private DriverContracts() { }
    @Schema(name="DriverCreate")
    public record Create(@NotBlank @Size(max=150) String name,
            @NotNull @Pattern(regexp="\\d{11}") String cpf,
            @NotNull @Pattern(regexp="\\d{11}") String cnh) {
        @Override public String toString() { return "DriverCreate[redacted]"; }
    }
    @Schema(name="DriverUpdate")
    public record Update(@Size(min=1,max=150) @Pattern(regexp="(?s).*\\S.*") String name,
            @Pattern(regexp="\\d{11}") String cpf,@Pattern(regexp="\\d{11}") String cnh) {
        @Override public String toString() { return "DriverUpdate[redacted]"; }
        @AssertTrue(message="Informe ao menos um campo")
        public boolean isNonEmpty() { return name!=null || cpf!=null || cnh!=null; }
    }
    @Schema(name="DriverStatusChange")
    public record StatusChange(@NotNull UserStatus status) { }
    @ParameterObject @Schema(name="DriverQuery")
    public record Query(UserStatus status,@Min(0) Integer page,@Min(1) @Max(100) Integer size) {
        public Query { page=page==null ? 0 : page;size=size==null ? 20 : size; }
    }
    @Schema(name="DriverView")
    public record View(UUID id,String name,String cpf,String cnh,UserStatus status,
            Instant registrationDate,Instant updatedAt) {
        @Override public String toString() { return "DriverView[redacted]"; }
    }
    @Schema(name="DriverPage")
    public record Page(List<View> items,int page,int size,long totalElements) {
        public Page { items=List.copyOf(items); }
    }
}
