package com.hospy.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** JSON body for login. */
@Data
public class AuthRequest {
    @NotBlank
    private String email;
    @NotBlank
    private String password;
    private String rol;
}
