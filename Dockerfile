FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN ./gradlew :main:shadowJar -x test -x integrationTest --no-daemon

FROM eclipse-temurin:21-jre AS runtime
RUN addgroup --system app && adduser --system --ingroup app app
WORKDIR /app
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "application.jar"]

FROM runtime AS production
COPY --from=build /app/main/build/libs/application.jar application.jar

FROM runtime AS dev
COPY main/build/libs/application.jar application.jar
