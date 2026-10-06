FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
RUN mvn -B verify

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN groupadd --gid 10001 travelmate && useradd --uid 10001 --gid 10001 --no-create-home travelmate && mkdir -p /app/data && chown 10001:10001 /app/data
COPY --from=build /build/target/travelmate-backend-1.0.0.jar /app/backend.jar
USER 10001:10001
EXPOSE 8787
ENTRYPOINT ["java","-jar","/app/backend.jar"]
