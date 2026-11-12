FROM eclipse-temurin:21.0.12_8-jdk-jammy AS build
WORKDIR /build
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY src src
RUN chmod +x mvnw && ./mvnw -B -DskipTests package
FROM eclipse-temurin:21.0.12_8-jre-jammy
WORKDIR /app
RUN groupadd --system knowledge && useradd --system --gid knowledge knowledge
COPY --from=build /build/target/java-knowledge-assistant-0.1.0.jar app.jar
USER knowledge
EXPOSE 8082
ENTRYPOINT ["java","-jar","/app/app.jar"]
