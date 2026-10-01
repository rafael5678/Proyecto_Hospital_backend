# ---------- Build ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -q -DskipTests dependency:go-offline

COPY src ./src
RUN ./mvnw -q -DskipTests package

# ---------- Runtime ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S hospy && adduser -S hospy -G hospy
USER hospy

COPY --from=build /app/target/Medico-*.jar /app/app.jar

# Render inyecta PORT; Spring lo lee en application.properties
ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "/app/app.jar"]
