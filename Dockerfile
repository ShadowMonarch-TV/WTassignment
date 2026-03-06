FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml ./
COPY src ./src
COPY public ./public

RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app

COPY --from=build /app/target/ds-quiz-app.jar /app/ds-quiz-app.jar
COPY --from=build /app/public /app/public

EXPOSE 8080
CMD ["java", "-jar", "/app/ds-quiz-app.jar"]
