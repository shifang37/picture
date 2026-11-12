package com.tzy.picturebackend.validation;

import com.tzy.picturebackend.annotation.StrongPassword;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * {@link StrongPassword} 的校验器：直接复用 {@link PasswordPolicy#checkFormat(String)}，
 * 保证接口参数校验与 Service 层校验使用同一套规则和提示文案。
 */
public class StrongPasswordValidator implements ConstraintValidator<StrongPassword, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // 空值交给 @NotBlank 处理，避免同一个字段出现两条重复提示
        if (value == null || value.isBlank()) {
            return true;
        }
        String error = PasswordPolicy.checkFormat(value);
        if (error == null) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(error).addConstraintViolation();
        return false;
    }
}
