package com.hospy.service;

final class TelefonoColombia {

    private TelefonoColombia() {}

    static String normalizar(String valor, String etiqueta) {
        if (valor == null) return null;
        String digitos = valor.replaceAll("\\D", "");
        if (digitos.length() > 10) digitos = digitos.substring(0, 10);
        boolean valido = digitos.isEmpty()
                || (digitos.length() == 10 && (digitos.startsWith("3") || digitos.startsWith("60")));
        if (!valido) {
            throw new RuntimeException(etiqueta + " debe tener 10 dígitos: un celular empieza por 3 y un fijo por 60.");
        }
        return digitos;
    }
}
