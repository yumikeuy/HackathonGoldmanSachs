# Built from the repository root (docker-compose: context ".", dockerfile backend/Dockerfile),
# so the React UI can be compiled into the backend's static resources.

# ---- 1. UI: npm build → /ui/dist (an empty dist while the frontend has no package.json yet)
FROM node:22-alpine AS ui
WORKDIR /ui
COPY frontend/ ./
RUN if [ -f package.json ]; then \
      (if [ -f package-lock.json ]; then npm ci; else npm install; fi) && npm run build; \
    else \
      mkdir -p dist; \
    fi

# ---- 2. Backend: dependencies first for layer caching, then sources + UI → jar
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY backend/pom.xml .
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
COPY --from=ui /ui/dist/ ./src/main/resources/static/
RUN mvn -q -B package -DskipTests

# ---- 3. Runtime
FROM eclipse-temurin:21-jre
COPY --from=build /app/target/*.jar /app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app.jar"]
