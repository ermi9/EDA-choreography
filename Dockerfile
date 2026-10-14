# The engine: built by `mvn package`, then copied in. One image serves the editor and API, and
# hosts services when HOSTED_SERVICES names them.
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --home /app engine && mkdir -p /flows && chown engine /flows
COPY target/eda-choreography-engine-*.jar app.jar
USER engine
EXPOSE 8080
ENV FLOWS_FOLDER=/flows
ENTRYPOINT ["java", "-jar", "app.jar"]
