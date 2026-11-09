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

    public static String slotLabel(LocalDateTime slot) {
        if (slot == null) return "";
        String[] meses = {"", "enero", "febrero", "marzo", "abril", "mayo", "junio",
                "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"};
        String dia = switch (slot.getDayOfWeek()) {
            case MONDAY -> "Lunes";
            case TUESDAY -> "Martes";
            case WEDNESDAY -> "Miércoles";
            case THURSDAY -> "Jueves";
            case FRIDAY -> "Viernes";
            case SATURDAY -> "Sábado";
            case SUNDAY -> "Domingo";
        };
        return dia + " " + slot.getDayOfMonth() + " de " + meses[slot.getMonthValue()]
                + " de " + slot.getYear() + " · "
                + String.format("%02d:%02d", slot.getHour(), slot.getMinute());
    }
}
