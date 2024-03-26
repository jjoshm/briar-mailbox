FROM gradle as builder
WORKDIR /app
COPY . /app
USER root
RUN chown -R gradle /app
USER gradle
RUN ./gradlew x86LinuxJar

FROM gradle
run mkdir -p /root/.local/share
workdir /app
copy --from=builder /app/mailbox-cli/build/libs/mailbox-cli-linux-x86_64.jar /app
entrypoint ["java", "-jar", "mailbox-cli-linux-x86_64.jar"]
