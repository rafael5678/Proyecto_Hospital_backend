package com.hospy.service;

import java.time.LocalDate;

/** Birth date: 1920 through 2021. Not today, not a future year. */
final class ColombianBirthDate {

    static final LocalDate EARLIEST = LocalDate.of(1920, 1, 1);
    static final LocalDate LATEST = LocalDate.of(2021, 12, 31);

    private ColombianBirthDate() {}

    static LocalDate requireAllowed(LocalDate date) {
        if (date == null) return null;
        if (date.isBefore(EARLIEST) || date.isAfter(LATEST)) {
            throw new RuntimeException("La fecha de nacimiento no puede ser después de 2021.");
        }
        return date;
    }
}
