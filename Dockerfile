# 1. Build Stage
FROM maven:3.9-eclipse-temurin-17-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
# Build the JAR, skipping tests to save time
RUN mvn clean package -DskipTests

# 2. Run Stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
# Copy the built JAR (matches any version)
COPY --from=build /app/target/*.jar app.jar

# Create folders for uploads
RUN mkdir -p /app/uploads

# Expose the port your app runs on
EXPOSE 8090

# Run the app
ENTRYPOINT ["java", "-jar", "app.jar"]