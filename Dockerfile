FROM jlesage/baseimage:alpine-3.10-glibc
ENV APP_HOME="/i2p"

WORKDIR /tmp/build
COPY . .

# Build package
RUN add-pkg openjdk8-jre
RUN add-pkg --virtual build-base gettext tar bzip2 apache-ant openjdk8
RUN echo "noExe=true" >> build.properties
RUN ant clean pkg
RUN del-pkg build-base gettext tar bzip2 apache-ant openjdk8

# "install" files
RUN mkdir -p ${APP_HOME}
RUN mv pkg-temp/* ${APP_HOME}

# "install" i2p by copying over installed files
COPY docker/rootfs/ /

# Mount home
VOLUME ["${APP_HOME}/.i2p"]

EXPOSE 7654 7656 7657 7658 4444 6668 8998 7659 7660 4445

# Metadata.
LABEL \
      org.label-schema.name="i2p" \
      org.label-schema.description="Docker container for I2P" \
      org.label-schema.version="1.0" \
      org.label-schema.vcs-url="https://github.com/i2p/i2p.i2p" \
      org.label-schema.schema-version="1.0"
