package br.com.olius.olius_core_api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Target({ElementType.FIELD,ElementType.PARAMETER,ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME) @Constraint(validatedBy=PairedCoordinatesValidator.class)
public @interface PairedCoordinates {
    String message() default "Latitude e longitude devem ser um par com até seis casas decimais ou ambas nulas.";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
