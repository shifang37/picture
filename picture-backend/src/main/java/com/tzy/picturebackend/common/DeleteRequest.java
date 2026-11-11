package com.tzy.picturebackend.common;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serializable;

@Data
public class DeleteRequest implements Serializable {

    /**
     * id
     */
    @NotNull(message = "id 不能为空")
    @Positive(message = "id 必须为正数")
    private Long id;

    private static final long serialVersionUID = 1L;
}
