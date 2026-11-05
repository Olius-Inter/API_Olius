package br.com.olius.olius_core_api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Target(ElementType.TYPE) @Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy=ProfileUpdateValidator.class)
public @interface ValidProfileUpdate {
    String message() default "Atualização cadastral inválida.";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
