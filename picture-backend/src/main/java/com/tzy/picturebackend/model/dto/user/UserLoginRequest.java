package com.tzy.picturebackend.model.dto.user;

import lombok.Data;

import java.io.Serializable;

@Data
public class UserLoginRequest implements Serializable {

    private static final long serialVersionUID = -3667464197811641436L;


    private String userAccount;

    private String userPassword;
}
