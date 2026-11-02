FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && sed -i 's/\r$//' mvnw
COPY src src
RUN ./mvnw -B verify

FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && groupadd --system banking && useradd --system --gid banking --home-dir /app banking
WORKDIR /app
COPY --from=build /build/target/banking-0.0.1-SNAPSHOT.jar app.jar
USER banking
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
