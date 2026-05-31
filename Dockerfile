# ========================================================
# Stage 1: Build the packaged distribution
# ========================================================
FROM gradle:8.8-jdk21-alpine AS builder
WORKDIR /app

# Copy configuration and source files
COPY --chown=gradle:gradle . .

# Fix Windows CRLF line endings and grant execute permission to the gradle wrapper
RUN tr -d '\r' < gradlew > gradlew.tmp && mv gradlew.tmp gradlew && chmod +x gradlew

# Compile and package everything using installDist (excludes tests for speed)
RUN ./gradlew installDist -x test --no-daemon

# ========================================================
# Stage 2: Create the super-lightweight runtime image
# ========================================================
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy the built distribution
COPY --from=builder /app/build/install/moba-trade ./moba-trade
# Copy the Shariah-compliant stocks database
COPY halal_stocks.json .

# Set up port & optimized JVM environment variables for 512MB RAM ceiling
EXPOSE 8080
ENV PORT=8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:ActiveProcessorCount=1 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

# Start the server using the compiled distribution script
CMD ["sh", "-c", "./moba-trade/bin/moba-trade"]
