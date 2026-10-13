FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY src ./src
RUN mkdir -p classes && find src/main/java -name '*.java' > sources.txt && javac --release 17 --add-modules jdk.httpserver -d classes @sources.txt && cp src/main/resources/* classes/ && jar --create --file switchyard.jar --main-class dev.switchyard.Main -C classes .
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/switchyard.jar /app/switchyard.jar
USER 10001:10001
ENTRYPOINT ["java", "--add-modules", "jdk.httpserver", "-cp", "/app/switchyard.jar"]
CMD ["dev.switchyard.Main", "/app/config/docker.properties"]
