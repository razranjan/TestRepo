# Use OpenJDK 11 base image
FROM openjdk:11-jre-slim

# Set working directory
WORKDIR /app

# Copy the built JAR file from the build output
# (Assuming the Gradle build creates a jar in build/libs/ directory)
COPY build/libs/*.jar app.jar

# Expose the service port (adjust for each service)
EXPOSE 8085

# Command to run the jar
CMD ["java", "-jar", "app.jar"]
