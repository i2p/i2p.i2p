FROM meeh/java8server:latest
# Docker image based on Alpine with Java.

# We use Oracle Java to run I2P, but uses the openjdk to build it.


MAINTAINER Mikal Villa <mikal@sigterm.no>

ENV GIT_BRANCH="master"
ENV I2P_PREFIX="/opt/i2p"
ENV PATH=${I2P_PREFIX}/bin:$PATH
ENV JAVA_HOME=/usr/lib/jvm/default-jvm

ENV GOSU_VERSION=1.7
ENV GOSU_SHASUM="34049cfc713e8b74b90d6de49690fa601dc040021980812b2f1f691534be8a50  /usr/local/bin/gosu"

RUN mkdir /user && adduser -S -h /user i2p && chown -R i2p:nobody /user

# Adding files first, since Docker.expt is required for installation
ADD Docker.expt /tmp/Docker.expt
ADD Docker.entrypoint.sh /entrypoint.sh

# Required for wget https
RUN apk add --no-cache openssl
# Gosu is a replacement for su/sudo in docker and not a backdoor :) See https://github.com/tianon/gosu
RUN wget -O /usr/local/bin/gosu https://github.com/tianon/gosu/releases/download/${GOSU_VERSION}/gosu-amd64 \
    && echo "${GOSU_SHASUM}" | sha256sum -c && chmod +x /usr/local/bin/gosu

#
# Each RUN is a layer, adding the dependencies and building i2pd in one layer takes around 8-900Mb, so to keep the
# image under 200mb we need to remove all the build dependencies in the same "RUN" / layer.
#

# The main layer
RUN apk --no-cache add build-base git gettext tar bzip2 apache-ant openjdk8 expect \
    && mkdir -p /usr/src/build \
    && cd /usr/src/build \
    && git clone -b ${GIT_BRANCH} https://github.com/i2p/i2p.i2p.git \
    && cd /usr/src/build/i2p.i2p \
    && echo "noExe=true" >> build.properties \
    && ant installer-linux \
    && cp i2pinstall*.jar /tmp/i2pinstall.jar \
    && mkdir -p /opt \
    && chown i2p:root /opt \
    && chmod u+rw /opt \
    && gosu i2p expect -f /tmp/Docker.expt \
    && cd ${I2P_PREFIX} \
    && rm -fr man docs *.bat *.command *.app /tmp/i2pinstall.jar /tmp/Docker.expt \
    && rm -fr /usr/src/build \
    && apk --purge del build-base apache-ant expect tcl expat git openjdk8 openjdk8-jre openjdk8-jre-base openjdk8-jre-lib bzip2 tar \
      binutils-libs binutils pkgconfig libcurl libc-dev musl-dev g++ make fortify-headers pkgconf giflib libssh2 libxdmcp libxcb \
      libx11 pcre alsa-lib libxi libxrender libxml2 readline bash openssl \
    && rm -fr /usr/lib/jvm/default-jre \
    && ln -sf /opt/jdk/jre /usr/lib/jvm/default-jre \
    && chmod a+x /entrypoint.sh



EXPOSE 7654 7656 7657 7658 4444 6668 8998 7659 7660 4445 15000-20000

ENTRYPOINT [ "/entrypoint.sh" ]

