FROM ubuntu:24.04

RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    ca-certificates curl make g++ openjdk-21-jdk-headless python3 verilator \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /work
COPY . /work
CMD ["make", "all"]
