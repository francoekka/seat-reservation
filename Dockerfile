# Build stage: the image contains Maven; the repository does not depend on a wrapper.
FROM maven:3.9.16-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -B -ntp clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
COPY --from=build /app/target/seat-reservation-*.jar app.jar

ENV PORT=8080
EXPOSE 8080
RUN useradd --system --uid 10001 --create-home appuser
USER 10001:10001

ENTRYPOINT ["sh", "-c", "exec java -XX:MaxRAMPercentage=75.0 -jar app.jar --server.port=${PORT:-8080}"]
