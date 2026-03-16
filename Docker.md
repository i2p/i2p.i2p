# I2P in Docker

I2P is an anonymous overlay network — a network within a network. It is intended to protect communication from surveillance and monitoring by third parties such as ISPs.

## Available Images

| Registry | Image | Pull Command |
|----------|-------|--------------|
| Docker Hub | `geti2p/i2p` | `docker pull geti2p/i2p` |
| GitHub Container Registry | `ghcr.io/i2p/i2p.i2p` | `docker pull ghcr.io/i2p/i2p.i2p` |

Both images are identical and support the following architectures:

| Architecture | Base Image | Java Version |
|-------------|------------|--------------|
| amd64 | Alpine Linux | OpenJDK 21 |
| arm64 (v8) | Alpine Linux | OpenJDK 21 |
| arm (v7) | Debian Bookworm | OpenJDK 17 |

---

## Quick Start

The fastest way to get I2P running:

```bash
docker run -d \
    --name i2p \
    -e JVM_XMX=512m \
    -v i2pconfig:/i2p/.i2p \
    -p 127.0.0.1:7657:7657 \
    -p 127.0.0.1:4444:4444 \
    geti2p/i2p:latest
```

Then open your browser to **http://127.0.0.1:7657** to access the I2P Router Console and complete the setup wizard.

> **Note:** Without setting `EXT_PORT`, the router will report a "Firewalled" status. This means it cannot receive incoming connections. See the [Environment Variables](#environment-variables) section for details.

---

## Docker Compose Examples

### Minimal

Runs the router console and HTTP proxy only. Good for trying I2P out.

```yaml
services:
  i2p:
    image: geti2p/i2p:latest
    restart: unless-stopped
    volumes:
      - ./i2pconfig:/i2p/.i2p
    ports:
      - "127.0.0.1:7657:7657"   # Router Console
      - "127.0.0.1:4444:4444"   # HTTP Proxy
```

### Recommended

Includes persistent volumes, memory limits, and an external port for non-firewalled operation. Replace `12345` with a random port number greater than 1024.

```yaml
services:
  i2p:
    image: geti2p/i2p:latest
    restart: unless-stopped
    environment:
      - JVM_XMX=512m
      - EXT_PORT=12345
    volumes:
      - ./i2pconfig:/i2p/.i2p
      - ./i2ptorrents:/i2psnark
    ports:
      - "127.0.0.1:7657:7657"   # Router Console
      - "127.0.0.1:4444:4444"   # HTTP Proxy
      - "127.0.0.1:6668:6668"   # IRC Proxy
      - "12345:12345"           # I2NP TCP
      - "12345:12345/udp"       # I2NP UDP
```

### Full (all services)

Exposes every available service. Only use this if you need all of them.

```yaml
services:
  i2p:
    image: geti2p/i2p:latest
    restart: unless-stopped
    environment:
      - JVM_XMX=512m
      - EXT_PORT=12345
    volumes:
      - ./i2pconfig:/i2p/.i2p
      - ./i2ptorrents:/i2psnark
    ports:
      - "127.0.0.1:4444:4444"   # HTTP Proxy
      - "127.0.0.1:4445:4445"   # HTTPS Proxy
      - "127.0.0.1:6668:6668"   # IRC Proxy
      - "127.0.0.1:7654:7654"   # I2CP Protocol
      - "127.0.0.1:7656:7656"   # SAM Bridge
      - "127.0.0.1:7657:7657"   # Router Console
      - "127.0.0.1:7658:7658"   # I2P Site (eepsite)
      - "127.0.0.1:7659:7659"   # SMTP Proxy
      - "127.0.0.1:7660:7660"   # POP3 Proxy
      - "12345:12345"           # I2NP TCP
      - "12345:12345/udp"       # I2NP UDP
```

---

## Docker Run Examples

### Minimal

```bash
docker run -d \
    --name i2p \
    -v i2pconfig:/i2p/.i2p \
    -p 127.0.0.1:7657:7657 \
    -p 127.0.0.1:4444:4444 \
    geti2p/i2p:latest
```

### Recommended (with external port)

Replace `54321` with a random port number greater than 1024.

```bash
docker run -d \
    --name i2p \
    -e JVM_XMX=512m \
    -e EXT_PORT=54321 \
    -v i2pconfig:/i2p/.i2p \
    -v i2ptorrents:/i2psnark \
    -p 127.0.0.1:4444:4444 \
    -p 127.0.0.1:6668:6668 \
    -p 127.0.0.1:7657:7657 \
    -p 54321:12345 \
    -p 54321:12345/udp \
    geti2p/i2p:latest
```

### Low memory (256MB heap)

```bash
docker run -d \
    --name i2p \
    -e JVM_XMX=256m \
    -v i2pconfig:/i2p/.i2p \
    -p 127.0.0.1:7657:7657 \
    -p 127.0.0.1:4444:4444 \
    geti2p/i2p:latest
```

---

## Environment Variables

| Variable | Default | Required | Description |
|----------|---------|----------|-------------|
| `JVM_XMX` | `512m` | No | Maximum Java heap size. Increase for better performance on systems with more RAM (e.g., `1024m`). Decrease on constrained devices (e.g., `256m`). |
| `EXT_PORT` | *unset* | Recommended | The external port for incoming I2NP connections. Without this, the router operates in "Firewalled" mode and cannot accept incoming connections, which reduces performance. Choose a random port above 1024 (e.g., `54321`). |
| `IP_ADDR` | *auto-detected* | No | IP address the router services bind to. Auto-detected from the container's hostname inside Docker. Set to `0.0.0.0` to listen on all interfaces, or a specific IP for custom networking setups. |

---

## Ports Reference

| Port | Protocol | Default Interface | Service | Enabled by Default | Description |
|------|----------|-------------------|---------|-------------------|-------------|
| 4444 | TCP | 127.0.0.1 | HTTP Proxy | Yes | Browse I2P websites (eepsites) by configuring your browser to use this as an HTTP proxy. |
| 4445 | TCP | 127.0.0.1 | HTTPS Proxy | Yes | HTTPS / CONNECT proxy for SSL connections over I2P. |
| 6668 | TCP | 127.0.0.1 | IRC Proxy | Yes | Connect an IRC client here to access I2P IRC networks (Irc2P). |
| 7654 | TCP | 127.0.0.1 | I2CP | Yes | I2P Client Protocol — used by external applications to communicate with the router. |
| 7656 | TCP | 127.0.0.1 | SAM Bridge | No | Simple Anonymous Messaging bridge — API for applications to use I2P (e.g., I2P-enabled BitTorrent clients). Must be enabled via the Router Console. |
| 7657 | TCP | 127.0.0.1 | Router Console | Yes | Web-based administration interface. This is the main UI for managing your I2P router. |
| 7658 | TCP | 127.0.0.1 | I2P Site | No | Host your own website (eepsite) on I2P. Must be enabled via the Router Console. |
| 7659 | TCP | 127.0.0.1 | SMTP Proxy | Yes | Send email through the I2P Postman mail system. Connect your mail client here. |
| 7660 | TCP | 127.0.0.1 | POP3 Proxy | Yes | Receive email from the I2P Postman mail system. Connect your mail client here. |
| 12345 | TCP+UDP | 0.0.0.0 | I2NP | Yes | I2P Network Protocol — the router's main communication port with other I2P routers. **This is the only port that should be exposed to the internet.** |

**Which ports should I publish?**

- **Always:** `7657` (Router Console) and `4444` (HTTP Proxy) — these are the minimum to use I2P.
- **Recommended:** `12345` / `EXT_PORT` (I2NP) — allows incoming connections, improving network integration and performance.
- **Optional:** `6668` (IRC), `7656` (SAM), and others as needed.

---

## Volumes

| Container Path | Required | Description |
|---------------|----------|-------------|
| `/i2p/.i2p` | Yes | Router configuration, keys, and runtime data. **You must persist this volume** — if lost, your router identity is regenerated and you lose all settings. |
| `/i2psnark` | No | Download directory for I2PSnark (the built-in BitTorrent client). Mount this to access downloaded files from the host. |

**Bind mount example:**
```bash
mkdir -p ./i2pconfig ./i2ptorrents
docker run -v ./i2pconfig:/i2p/.i2p -v ./i2ptorrents:/i2psnark ...
```

**Named volume example:**
```bash
docker run -v i2pconfig:/i2p/.i2p -v i2ptorrents:/i2psnark ...
```

---

## Building from Source

Build the image locally:

```bash
docker build -t i2p .
```

Multi-architecture build (requires Docker Buildx):

```bash
docker buildx build --platform linux/amd64,linux/arm64,linux/arm/v7 -t i2p .
```

---

## Networking and Security

### External Port Selection

The default I2NP port is `12345`. You should **change this to a random port** greater than 1024 when deploying. Using the default port makes it easy for others to identify that you are running I2P in Docker.

```bash
# Pick a random port
EXT_PORT=$(shuf -i 1025-65535 -n 1)
echo "Using port: $EXT_PORT"
```

### Firewalled vs Non-Firewalled

- **Firewalled** (no `EXT_PORT` set): The router can only make outgoing connections. This works but reduces performance and your ability to contribute to the network.
- **Non-Firewalled** (`EXT_PORT` set and port published): The router accepts incoming connections. This improves performance and helps the I2P network.

To achieve non-firewalled status, you must:
1. Set the `EXT_PORT` environment variable
2. Publish that port for both TCP and UDP
3. Ensure your firewall/router allows traffic on that port

### Binding and Remote Access

All services except I2NP are bound to `127.0.0.1` by default. This means they are only accessible from the Docker host machine (via published ports) and not from the network.

To access the Router Console from a remote machine, use an SSH tunnel:

```bash
ssh -L 7657:127.0.0.1:7657 user@your-server
# Then open http://127.0.0.1:7657 in your local browser
```

Alternatively, set `IP_ADDR=0.0.0.0` to bind services to all interfaces — but **only do this on a trusted network**, as it exposes the console and proxies to anyone who can reach the container.

---

## Advanced Configuration

### Custom router.config

You can mount a custom configuration file to override defaults:

```bash
docker run -v ./my-router.config:/i2p/router.config ...
```

The default settings disable automatic updates (`router.updateDisabled=true`) since the Docker image should be updated by pulling a new image.

### Enabling the SAM Bridge

The SAM (Simple Anonymous Messaging) bridge allows external applications to use the I2P network. It is disabled by default. To enable it:

1. Open the Router Console at http://127.0.0.1:7657
2. Navigate to **I2CP** settings or **Clients** configuration
3. Enable the SAM Bridge client

Then publish port `7656` to use it from the host:

```bash
docker run -p 127.0.0.1:7656:7656 ...
```

### Hosting an I2P Site (Eepsite)

The built-in web server (eepsite) is disabled by default. To enable it:

1. Open the Router Console at http://127.0.0.1:7657
2. Navigate to **Hidden Services Manager**
3. Start the "I2P Webserver" tunnel

Place your website files in the eepsite directory within the config volume.

---

## Troubleshooting

### Router shows "Firewalled" status

Set the `EXT_PORT` environment variable and publish the port:

```bash
docker run -e EXT_PORT=54321 -p 54321:12345 -p 54321:12345/udp ...
```

Ensure the port is open in your host firewall and any upstream router/NAT.

### Cannot access the Router Console

- Verify port `7657` is published: `docker port i2p`
- Ensure you're binding to `127.0.0.1:7657` on the host
- Check that the container is running: `docker ps`
- Check container logs: `docker logs i2p`

### High memory usage

Lower the JVM heap limit:

```bash
docker run -e JVM_XMX=256m ...
```

The default is `512m`. For systems with limited RAM (e.g., Raspberry Pi), `256m` works but may reduce performance.

### Container won't start

Check the logs for errors:

```bash
docker logs i2p
```

Common causes:
- Volume permission issues — ensure the mounted directories are writable
- Port conflicts — check if another process is using the same ports

---

## Updating

Since automatic updates are disabled in the Docker image, update by pulling the latest image:

```bash
docker pull geti2p/i2p:latest
docker stop i2p
docker rm i2p
# Re-run your docker run command
```

Or with Docker Compose:

```bash
docker compose pull
docker compose up -d
```

Your configuration and router identity are preserved in the `/i2p/.i2p` volume.
