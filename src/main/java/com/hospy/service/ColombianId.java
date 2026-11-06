package com.hospy.service;

/** Colombian NUIP: at most 10 digits. A short label with letters, such as CC-DEMO, is kept. */
final class ColombianId {

    private ColombianId() {}

    static String normalize(String value) {
        if (value == null) return null;
        String text = value.trim();
        if (text.isEmpty()) return text;
        String digits = text.replaceAll("\\D", "");
        if (digits.length() > 10) {
            throw new RuntimeException("La cédula colombiana tiene máximo 10 dígitos.");
        }
        if (text.chars().allMatch(Character::isDigit)) return text;
        if (digits.length() >= 6) return digits;
        return text.length() > 20 ? text.substring(0, 20) : text;
    }
}
