# syntax=docker/dockerfile:1
# Shared root build context; Compose selects backend or frontend target.
FROM eclipse-temurin:21-jdk AS backend-build
WORKDIR /build
COPY backend/.mvn .mvn
COPY backend/mvnw backend/pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B dependency:go-offline
COPY backend/src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B verify

FROM eclipse-temurin:21-jre-jammy AS backend
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system banking \
    && useradd --system --gid banking --home-dir /app banking
WORKDIR /app
COPY --from=backend-build --chown=banking:banking /build/target/banking-0.0.1-SNAPSHOT.jar app.jar
USER banking
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM node:24-alpine AS frontend-build
WORKDIR /build
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY frontend/ ./
RUN npm run build

FROM nginx:stable-alpine AS frontend
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=frontend-build /build/dist /usr/share/nginx/html
EXPOSE 80
