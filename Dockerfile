# Stage 1: build the shaded JAR. Tests run here, so a red suite never produces an image.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package

# Stage 2: slim Java 21 runtime, non-root, no shell scripts.
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S adr && adduser -S adr -G adr
WORKDIR /app
COPY --from=build /build/target/adr-demo.jar ./adr-demo.jar
COPY jvm.options ./jvm.options
USER adr
EXPOSE 8080
ENTRYPOINT ["java", "@jvm.options", "-jar", "adr-demo.jar"]
