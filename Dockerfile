# ---- Frontend build stage ----
FROM node:20-alpine AS frontend-build
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm install
COPY frontend ./
ARG VITE_API_BASE_URL=""
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN npm run build

# ---- Backend build stage ----
FROM maven:3.9.9-eclipse-temurin-21 AS backend-build
WORKDIR /app

COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline

COPY src src

# Copy frontend build into Spring Boot static resources
COPY --from=frontend-build /app/frontend/dist src/main/resources/static

RUN mvn -q -Dmaven.test.skip=true clean package

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=backend-build /app/target/order-tracking-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
