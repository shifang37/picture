package com.tzy.picturebackend.annotation;

import com.tzy.picturebackend.validation.StrongPasswordValidator;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 密码强度校验注解：规则与 {@link com.tzy.picturebackend.validation.PasswordPolicy} 保持一致。
 * <p>
 * 只负责密码本身的强度；密码与账号的关系由 Service 层带账号信息再校验。
 */
@Documented
@Constraint(validatedBy = StrongPasswordValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface StrongPassword {

    String message() default "密码强度不足";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
