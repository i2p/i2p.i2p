# Use a multi-stage build to reduce the size of the resulting image
# We need alpine >v3 in order to install an apache-ant > 1.9
FROM alpine:3 as builder
ENV I2P_PREFIX="/opt/i2p"

WORKDIR /tmp/build
COPY . ./

# Build installer
RUN apk --no-cache add build-base gettext tar bzip2 apache-ant openjdk8 expect
RUN echo "noExe=true" >> build.properties
RUN ant installer-linux
RUN mkdir -p /opt
RUN mv i2pinstall*.jar /tmp/i2pinstall.jar

# Install i2p using the installer into I2P_PREFIX
RUN expect -f ./Docker.expt
RUN cd ${I2P_PREFIX}
RUN rm -fr man docs *.bat *.command *.app

# Second stage only using the installer from the last stage
# ---------------------------------------------------------
# We can't use alpine here as the java service wrapper is built with glibc
# alpine uses musl
FROM openjdk:11.0-jre-slim

ARG I2P_UID=1000
ARG I2P_USER=i2p
ENV I2P_PREFIX="/opt/i2p"
ENV PATH=${I2P_PREFIX}/bin:$PATH

# "install" i2p by copying over installed files
COPY --from=builder /opt/i2p ${I2P_PREFIX}

# Setup user and fix permissions in
RUN adduser --system --uid ${I2P_UID} --home /user ${I2P_USER} \
    && chown -R ${I2P_USER} /user \
    && chown -R ${I2P_USER} ${I2P_PREFIX} \
    && chmod -R u+rwx ${I2P_PREFIX}

EXPOSE 7654 7656 7657 7658 4444 6668 8998 7659 7660 4445 15000-20000

USER i2p
ENTRYPOINT [ "/opt/i2p/i2psvc" ]
CMD [ "/opt/i2p/wrapper.config", "wrapper.pidfile=/var/tmp/i2p.pid", "wrapper.name=i2p", "wrapper.displayname=\"I2P Service\"" , "wrapper.statusfile=/var/tmp/i2p.status", "wrapper.java.statusfile=/var/tmp/i2p.java.status", "wrapper.logfile=/var/tmp/wrapper.log" ]

