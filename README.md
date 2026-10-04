# Hospy Backend (Spring Boot)

Despliegue Docker en Render. Health: `/api/health`

Variables (panel Render, no en git):
- SPRING_DATASOURCE_URL
- SPRING_DATASOURCE_USERNAME
- SPRING_DATASOURCE_PASSWORD
- JWT_SECRET
- JWT_EXPIRATION=86400000
- CORS_ORIGINS=https://*.vercel.app,http://localhost:4200
- SPRING_PROFILES_ACTIVE=prod
- OPENAI_API_KEY (opcional; si falta, usa datasets y el clasificador de piel local)

<!-- Documentación auditada y sincronizada para despliegue en producción -->
