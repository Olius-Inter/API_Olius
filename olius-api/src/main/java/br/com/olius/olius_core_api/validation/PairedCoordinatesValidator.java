package br.com.olius.olius_core_api.validation;

import br.com.olius.olius_core_api.dto.IdentityRequests.AddressInput;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PairedCoordinatesValidator implements ConstraintValidator<PairedCoordinates,AddressInput> {
    @Override public boolean isValid(AddressInput value,ConstraintValidatorContext context) {
        if(value==null) return true;
        if((value.latitude()==null)!=(value.longitude()==null)) return false;
        return value.latitude()==null || (value.latitude().stripTrailingZeros().scale()<=6
            && value.longitude().stripTrailingZeros().scale()<=6);
    }
}
