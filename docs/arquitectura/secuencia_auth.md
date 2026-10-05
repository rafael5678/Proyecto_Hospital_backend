# Diagrama de Secuencia de Login

1. Cliente envía POST con credenciales.
2. AuthService valida hash BCrypt.
3. JwtTokenProvider firma token de 10 min.
4. Retorno de AuthResponse con roles.
