ARG TARGETARCH

# --- Single builder (Java bytecode is platform-independent) ---

FROM --platform=linux/amd64 alpine:latest AS builder

ENV APP_HOME="/i2p"
ARG ANT_VERSION="1.10.17"

WORKDIR /tmp/build
COPY . .

RUN apk add --no-cache gettext tar bzip2 curl openjdk21 \
    && echo "build.built-by=Docker" >> override.properties \
    && curl https://dlcdn.apache.org//ant/binaries/apache-ant-${ANT_VERSION}-bin.tar.bz2 | tar -jxf - -C /opt \
    && /opt/apache-ant-${ANT_VERSION}/bin/ant preppkg-linux-only \
    && rm -rf pkg-temp/osid pkg-temp/lib/wrapper pkg-temp/lib/wrapper.*

# --- Runtime stages per architecture ---

FROM debian:bookworm-slim AS runtime-amd64
RUN apt-get update && apt-get install -y --no-install-recommends openjdk-17-jre-headless fonts-dejavu \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

FROM debian:bookworm-slim AS runtime-arm64
RUN apt-get update && apt-get install -y --no-install-recommends openjdk-17-jre-headless fonts-dejavu \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

FROM debian:bookworm-slim AS runtime-arm
RUN apt-get update && apt-get install -y --no-install-recommends openjdk-17-jre-headless fonts-dejavu \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

# Select runtime based on target architecture
FROM runtime-${TARGETARCH}

ENV APP_HOME="/i2p"
WORKDIR ${APP_HOME}
COPY --from=builder /tmp/build/pkg-temp .

# "install" i2p by copying over installed files
COPY --chown=root:root docker/rootfs/ /
RUN chmod +x /startapp.sh

# Mount home and snark
VOLUME ["${APP_HOME}/.i2p"]
VOLUME ["/i2psnark"]

EXPOSE 7654 7656 7657 7658 4444 6668 7659 7660 4445 12345

# Metadata.
LABEL \
      org.label-schema.name="i2p" \
      org.label-schema.description="Docker container for I2P" \
      org.label-schema.version="1.0" \
      org.label-schema.vcs-url="https://github.com/i2p/i2p.i2p" \
      org.label-schema.schema-version="1.0"

RUN groupadd -r i2p && useradd -r -g i2p -d /i2p -s /sbin/nologin i2p \
 && chown -R i2p:i2p /i2p
USER i2p

ENTRYPOINT ["/startapp.sh"]
