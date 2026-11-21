# ── Build stage ────────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /workspace

# Cache Maven dependencies before copying source (layer is reused when pom.xml
# hasn't changed).
COPY pom.xml .
RUN apk add --no-cache maven && mvn dependency:go-offline -q

COPY src ./src
RUN mvn package -DskipTests -q

# ── Runtime stage ───────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Non-root user for security
RUN addgroup -S wallet && adduser -S wallet -G wallet
USER wallet

COPY --from=build /workspace/target/wallet-ledger-*.jar app.jar

# Expose the default Spring Boot port
EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]
