FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src src
RUN mvn -B -q -DskipTests package
FROM eclipse-temurin:25-jre
WORKDIR /app
RUN mkdir /data && chown 10001:10001 /data
COPY tools/health.sh /app/health.sh
COPY --from=build /workspace/target/*.jar app.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50 -XX:+ExitOnOutOfMemoryError"
USER 10001:10001
EXPOSE 8081
HEALTHCHECK --interval=10s --timeout=5s --start-period=40s CMD ["bash", "/app/health.sh"]
ENTRYPOINT ["java","-jar","/app/app.jar"]
