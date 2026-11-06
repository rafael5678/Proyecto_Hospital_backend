package com.hospy.service;

final class ColombianPhone {

    private ColombianPhone() {}

    static String normalize(String value, String label) {
        if (value == null) return null;
        String digits = value.replaceAll("\\D", "");
        if (digits.length() > 10) digits = digits.substring(0, 10);
        boolean valid = digits.isEmpty()
                || (digits.length() == 10 && (digits.startsWith("3") || digits.startsWith("60")));
        if (!valid) {
            throw new RuntimeException(label + " debe tener 10 dígitos: un celular empieza por 3 y un fijo por 60.");
        }
        return digits;
    }
}
