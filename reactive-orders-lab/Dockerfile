# syntax=docker/dockerfile:1
# Deux étapes : Maven et les sources ne sont pas embarqués dans l'image finale.
# Les tags fixent Maven/Java. Pour une livraison immuable, fixer aussi les digests
# après validation du build et faire suivre leurs mises à jour par un robot.
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./
COPY src ./src
# Le cache contient les dépendances, pas des secrets. La CI exécute les tests
# avant le build de livraison ; ici skipTests évite de les rejouer dans Docker.
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app
# curl sert uniquement à la sonde HTTP ; le JRE suffit à exécuter le service.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build --chown=10001:10001 /workspace/target/*.jar /app/app.jar
# Un utilisateur numérique sans privilèges réduit les droits du processus Java.
USER 10001:10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
# Une sonde HTTP teste un serveur qui répond, pas seulement un port ouvert.
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=12 \
    CMD curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health || exit 1
# Forme exec : Java reçoit directement SIGTERM et peut arrêter les flux proprement.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
