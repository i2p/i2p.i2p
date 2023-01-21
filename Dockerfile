FROM jlesage/baseimage:alpine-3.15-glibc as builder

ENV APP_HOME="/i2p"

WORKDIR /tmp/build
COPY . .

RUN add-pkg --virtual build-base gettext tar bzip2 apache-ant openjdk17 \
    && ant preppkg-linux-only \
    && rm -rf pkg-temp/osid pkg-temp/lib/wrapper pkg-temp/lib/wrapper.* \
    && del-pkg build-base gettext tar bzip2 apache-ant openjdk17

FROM jlesage/baseimage:alpine-3.15-glibc
ENV APP_HOME="/i2p"

RUN add-pkg openjdk17-jre ttf-dejavu
WORKDIR ${APP_HOME}
COPY --from=builder /tmp/build/pkg-temp .

# "install" i2p by copying over installed files
COPY docker/rootfs/ /

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

