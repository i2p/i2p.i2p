FROM eclipse-temurin:17-alpine as builder

ENV APP_HOME="/i2p"
ARG ANT_VERSION="1.10.15"

WORKDIR /tmp/build
COPY . .

RUN apk add --no-cache gettext tar bzip2 curl \
    && echo "build.built-by=Docker" >> override.properties \
    && curl https://dlcdn.apache.org//ant/binaries/apache-ant-${ANT_VERSION}-bin.tar.bz2 | tar -jxf - -C /opt \
    && /opt/apache-ant-${ANT_VERSION}/bin/ant preppkg-linux-only \
    && rm -rf pkg-temp/osid pkg-temp/lib/wrapper pkg-temp/lib/wrapper.*

FROM eclipse-temurin:17-alpine
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

ENTRYPOINT ["/startapp.sh"]
