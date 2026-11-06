package com.hospy.service;

/** NUIP de la Registraduría: máximo 10 dígitos. Un texto corto con letras, como CC-DEMO, se conserva. */
final class CedulaColombia {

    private CedulaColombia() {}

    static String normalizar(String valor) {
        if (valor == null) return null;
        String texto = valor.trim();
        if (texto.isEmpty()) return texto;
        String digitos = texto.replaceAll("\\D", "");
        if (digitos.length() > 10) {
            throw new RuntimeException("La cédula colombiana tiene máximo 10 dígitos.");
        }
        if (texto.chars().allMatch(Character::isDigit)) return texto;
        if (digitos.length() >= 6) return digitos;
        return texto.length() > 20 ? texto.substring(0, 20) : texto;
    }
}
