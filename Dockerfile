# ========================================================
# Stage 1: Build the packaged distribution
# ========================================================
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app

# Copy everything into the container
COPY . .

# Fix Windows CRLF line endings that cause "bad interpreter" errors on Linux
RUN sed -i 's/\r$//' gradlew
RUN chmod +x gradlew

# Compile and package everything using the project's exact Gradle wrapper
RUN ./gradlew installDist -x test --no-daemon

# ========================================================
# Stage 2: Create the super-lightweight runtime image
# ========================================================
FROM eclipse-temurin:21-jre
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
