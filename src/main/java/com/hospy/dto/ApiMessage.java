package com.hospy.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** Simple JSON message the API returns. */
@Data
@AllArgsConstructor
public class ApiMessage {
    private String mensaje;
}
