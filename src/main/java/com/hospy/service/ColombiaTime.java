package com.hospy.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Hospital clock. Appointment hours are Colombia wall time, not the server's UTC clock. */
public final class ColombiaTime {
    public static final ZoneId ZONE = ZoneId.of("America/Bogota");

    private ColombiaTime() {}

    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }
}
