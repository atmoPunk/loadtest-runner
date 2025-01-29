FROM ubuntu:22.04 AS builder

ENV SSH_LOGIN=loadtest-runner
ENV SSH_FILE=/opt/.ssh/generated
RUN apt update && apt install -y openssh-client
RUN mkdir -p /opt/.ssh && ssh-keygen -t rsa -f /opt/.ssh/generated -C loadtest-runner

FROM eclipse-temurin:17
COPY --from=builder /opt/.ssh /opt/.ssh
ENV SSH_LOGIN=loadtest-runner
ENV SSH_FILE=/opt/.ssh/generated