package br.com.olius.olius_core_api.validation;

import br.com.olius.olius_core_api.dto.ProfileUpdate;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.HashSet;

public class ProfileUpdateValidator implements ConstraintValidator<ValidProfileUpdate,ProfileUpdate> {
    /** Valida presença e duplicatas; tamanhos e itens pertencem às constraints dos campos. */
    @Override public boolean isValid(ProfileUpdate value,ConstraintValidatorContext context) {
        if(value==null) return true;
        context.disableDefaultConstraintViolation();
        boolean valid=true;
        if(!value.hasName() && !value.hasTelephones()) {
            invalid(context,"_request"); valid=false;
        }
        if(value.hasName() && (value.getName()==null || value.getName().isBlank())) {
            invalid(context,"name"); valid=false;
        }
        if(value.hasTelephones() && (value.getTelephones()==null ||
            new HashSet<>(value.getTelephones()).size()!=value.getTelephones().size())) {
            invalid(context,"telephones"); valid=false;
        }
        return valid;
    }
    private static void invalid(ConstraintValidatorContext context,String field) {
        context.buildConstraintViolationWithTemplate("Valor inválido para este campo.")
            .addPropertyNode(field).addConstraintViolation();
    }
}
