package br.com.olius.olius_core_api.validation;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
public class StrongPasswordValidator implements ConstraintValidator<StrongPassword,String> {

    @Override public boolean isValid(String value, ConstraintValidatorContext context) {
        return valid(value);
    }

    public static boolean valid(String value) {
        if(value==null) return false;
        int length=value.codePointCount(0,value.length());
        return length>=10 && length<=128 && value.codePoints().anyMatch(Character::isUpperCase)
            && value.codePoints().anyMatch(Character::isDigit)
            && value.codePoints().anyMatch(c -> !Character.isLetterOrDigit(c) && !Character.isWhitespace(c));
    }
}
