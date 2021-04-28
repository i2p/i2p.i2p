# I2P in Docker

### Building an image
There is an i2P image available over at [DockerHub](https://hub.docker.com).  If you do not want to use that one, you can build one yourself:
```
docker build -t i2p .
```

### Running a container

#### Volumes
The container requires a volume for the configuration data to be mounted.  Optionally, you can mount a separate volume for torrent ("i2psnark") downloads.  See the example below.

#### Memory usage
By the default the image limits the memory available to the Java heap to 512MB.  You can override that with the `JVM_XMX` environment variable.

#### Ports
There are several ports which are exposed by the image.  You can choose which ones to publish depending on your specific needs.

|Port|Description|TCP/UDP|
|---|---|---|
|4444|HTTP Proxy|TCP|
|4445|HTTPS Proxy|TCP|
|6668|IRC Proxy|TCP|
|7654|I2CP Protocol|TCP|
|7656|SAM Bridge TCP|TCP|
|7657|Router console|TCP|
|7658|I2P Site|TCP|
|7659|SMTP Proxy|TCP|
|7660|POP Proxy|TCP|
|12345|I2NP Protocol|TCP and UDP|

You probably want at least the Router Console (7657)  and the HTTP Proxy (4444).  If you want I2P to be able to receive incoming connections from the internet, and hence not think it's firewalled, publish the I2NP Protocol port (12345) - but make sure you publish to a different random port, otherwise others may be able to guess you're running I2P in a Docker image.

#### Example
Here is an example container that mounts `i2phome` as home directory, `i2ptorrents` for torrents, and opens HTTP Proxy, IRC, Router Console and I2NP Protocols.  It also limits the memory available to the JVM to 256MB.
```
docker run \
    -e JVM_XMX=256m \
    -v i2phome:/i2p/.i2p \
    -v i2ptorrents:/i2psnark \
    -p 4444:4444 \
    -p 6668:6668 \
    -p 7657:7657 \
    -p 54321:12345 \
    -p 54321:12345/udp \  # I2NP port needs TCP and UDP.  Change the 54321 to something random, greater than 1024.
    i2p:latest
```

