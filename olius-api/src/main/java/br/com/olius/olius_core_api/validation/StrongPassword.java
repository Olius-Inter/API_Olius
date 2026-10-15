package br.com.olius.olius_core_api.validation;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy=StrongPasswordValidator.class)
public @interface StrongPassword {
    String message() default "Senha deve ter de 10 a 128 caracteres, maiúscula, número e símbolo.";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
