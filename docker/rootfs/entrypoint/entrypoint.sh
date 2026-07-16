#!/bin/sh
#####################################################################
## Container Entrypoint
# Bootstraps and launches the I2P router process in the container.
#
# Responsibilities:
# - load shared helper functions and validate required environment
# - initialize and update I2P config files from bundled templates
# - apply runtime settings (ports, bind IP, JVM heap) from env vars
# - mirror key router log files to stdout for container logging
# - run the router as user "i2p", forward termination signals, and
#   normalize graceful shutdown exit codes for orchestrators
set -e

if [ -n "$DEBUG_ENTRYPOINT" ]; then
    set -x
fi

# Source functions
if [ -f /entrypoint/functions.sh ]; then
    . /entrypoint/functions.sh
else
    echo "FATAL: /entrypoint/functions.sh was not found."
    exit 1
fi

# Optionally remap the i2p account IDs to match host bind mount ownership.
# This must happen before ownership fixes and before launching the router.
current_i2p_uid="$(id -u i2p)"
current_i2p_gid="$(id -g i2p)"
target_i2p_uid="${I2P_UID:-$current_i2p_uid}"
target_i2p_gid="${I2P_GID:-$current_i2p_gid}"

if [ -n "$I2P_UID" ] || [ -n "$I2P_GID" ]; then
    case "$target_i2p_uid" in
        ''|*[!0-9]*)
            log_error "I2P_UID must be numeric. Received: $target_i2p_uid"
            exit 1
            ;;
    esac
    case "$target_i2p_gid" in
        ''|*[!0-9]*)
            log_error "I2P_GID must be numeric. Received: $target_i2p_gid"
            exit 1
            ;;
    esac

    if [ "$target_i2p_gid" != "$current_i2p_gid" ]; then
        existing_group_name="$(getent group "$target_i2p_gid" | awk -F: 'NR==1 {print $1}')"
        if [ -n "$existing_group_name" ] && [ "$existing_group_name" != "i2p" ]; then
            log_error "Cannot set I2P_GID=$target_i2p_gid because it is already used by group \"$existing_group_name\"."
            exit 1
        fi
        log_info "Remapping i2p group GID: $current_i2p_gid -> $target_i2p_gid"
        groupmod -g "$target_i2p_gid" i2p
        current_i2p_gid="$target_i2p_gid"
    fi

    if [ "$target_i2p_uid" != "$current_i2p_uid" ]; then
        existing_user_name="$(getent passwd "$target_i2p_uid" | awk -F: 'NR==1 {print $1}')"
        if [ -n "$existing_user_name" ] && [ "$existing_user_name" != "i2p" ]; then
            log_error "Cannot set I2P_UID=$target_i2p_uid because it is already used by user \"$existing_user_name\"."
            exit 1
        fi
        log_info "Remapping i2p user UID: $current_i2p_uid -> $target_i2p_uid"
        usermod -u "$target_i2p_uid" i2p
        current_i2p_uid="$target_i2p_uid"
    fi
fi

# Copy I2P default configs to installation directory
cp -f /entrypoint/i2p-config-templates/*.config "${I2P_DIR_BASE}/"

# Check if specifc JVM heap limit was set.
if [ -z "$JVM_XMX" ]; then
    log_info "Default JVM max heap is 512m. Set JVM_XMX to override, e.g. \"JVM_XMX=1024m\"."
    JVM_XMX=512m
fi

# Check if external I2P I2NP port was set.
if [ -z "$EXT_PORT" ]; then
    log_warn "EXT_PORT is not set. Using default port 12345. The router may appear \"Firewalled\" unless this port is published and reachable. Set EXT_PORT in your Compose file or docker run command, and ensure the chosen port is free and forwarded to the container."
    EXT_PORT=12345
fi
# Warn if I2P I2NP port was set to the default 12345.
if [ "$EXT_PORT" = "12345" ]; then
    log_warn "EXT_PORT is set to the default value ($EXT_PORT). For better privacy and reduced fingerprinting, consider using a non-default port."
fi

# Choose a bind address for local services defined in the installation
# clients.confg and i2ptunnel.config files, as well in the files inside
# "clients.config.d/" and "i2ptunnel.config.d/" in the configuration directory.
# Use "IP_ADDR" env if this was set, otherwise derive one from the container.
if [ -z "$IP_ADDR" ]; then
    log_info "IP_ADDR is not set. Deriving local services bind address from container networking. Set IP_ADDR to override."
    if IP_ADDR="$(get_private_bind_ipv4)"; then
        log_info "Derived I2P router local services bind address: $IP_ADDR"
    else
        log_warn "Failed to derive I2P router local services bind address. Using \"127.0.0.1\"."
        IP_ADDR="127.0.0.1"
    fi
fi

# Ensure installation router.config is set with env provided parameters.
# This file is used in a new router initialization.
update_config_value "${I2P_DIR_BASE}/router.config" '^i2np\.ntcp\.port$' "$EXT_PORT"
update_config_value "${I2P_DIR_BASE}/router.config" '^i2np\.udp\.port$' "$EXT_PORT"
update_config_value "${I2P_DIR_BASE}/router.config" '^i2np\.udp\.internalPort$' "$EXT_PORT"
update_config_value "${I2P_DIR_BASE}/router.config" '^i2p\.dir\.base$' "$I2P_DIR_BASE"
update_config_value "${I2P_DIR_BASE}/router.config" '^i2p\.dir\.config$' "$I2P_DIR_CONFIG"
update_config_value "${I2P_DIR_BASE}/router.config" '^i2cp\.hostname$' "$IP_ADDR"

# Ensure installation clients.config is set with env provided parameters.
# This file is used in a new router initialization.
update_config_value "${I2P_DIR_BASE}/clients.config" '^clientApp\.0\.args$' "7657 ${IP_ADDR} ./webapps/" '^7657 ::1,127\.0\.0\.1 ./webapps/$'
update_config_value "${I2P_DIR_BASE}/clients.config" '^clientApp\.1\.args$' "sam.keys ${IP_ADDR} 7656 i2cp.tcp.host=${IP_ADDR} i2cp.tcp.port=7654" '^sam\.keys 127\.0\.0\.1 7656 i2cp\.tcp\.host=127\.0\.0\.1 i2cp\.tcp\.port=7654$'
update_config_value "${I2P_DIR_BASE}/clients.config" '^clientApp\.3\.args$' "\"${EEPSITE_DIR}/jetty.xml\""
update_config_value "${I2P_DIR_BASE}/i2ptunnel.config" '^tunnel\.[0-9]+\.interface$' "${IP_ADDR}" '^127\.0\.0\.1$'
update_config_value "${I2P_DIR_BASE}/i2ptunnel.config" '^tunnel\.3\.targetHost$' "${IP_ADDR}" '^127\.0\.0\.1$'
update_config_value "${I2P_DIR_BASE}/i2psnark.config" '^i2psnark\.dir$' "${I2PSNARK_DIR}"
update_config_value "${I2P_DIR_BASE}/i2psnark.config" '^i2psnark\.i2cpHost$' "${IP_ADDR}" '^127\.0\.0\.1$'

# Ensure installation eepsite jetty xml is set with env provided parameters.
# This jetty file is used in a new router initialization for the built-in eepsite.
update_jetty_xml_set_value "${I2P_DIR_BASE}/eepsite/jetty.xml" 'host' "${IP_ADDR}"

# Ensure configuration router.config is updated with env provided parameters.
# This file is the persistent config used when restarting an existing router
if [ -f "${I2P_DIR_CONFIG}/router.config" ]; then
    update_config_value "${I2P_DIR_CONFIG}/router.config" '^i2np\.ntcp\.port$' "$EXT_PORT"
    update_config_value "${I2P_DIR_CONFIG}/router.config" '^i2np\.udp\.port$' "$EXT_PORT"
    update_config_value "${I2P_DIR_CONFIG}/router.config" '^i2np\.udp\.internalPort$' "$EXT_PORT"
    update_config_value "${I2P_DIR_CONFIG}/router.config" '^i2p\.dir\.base$' "$I2P_DIR_BASE"
    update_config_value "${I2P_DIR_CONFIG}/router.config" '^i2p\.dir\.config$' "$I2P_DIR_CONFIG"
    update_config_value "${I2P_DIR_CONFIG}/router.config" '^i2cp\.hostname$' "$IP_ADDR"
fi

# Ensure configuration files in "clients.config.d/" and "i2ptunnel.config.d/"
# are updated with env provided parameters.
# This file is the persistent config used when restarting an existing router.
if [ -d "${I2P_DIR_CONFIG}/clients.config.d" ]; then
    find "${I2P_DIR_CONFIG}/clients.config.d" -type f -name '*net.i2p.router.web.RouterConsoleRunner*.config' -print | while IFS= read -r cfg_file; do
        update_config_value "$cfg_file" '^clientApp\.0\.args$' "7657 ${IP_ADDR} ./webapps/"
    done
    find "${I2P_DIR_CONFIG}/clients.config.d" -type f -name '*net.i2p.sam.SAMBridge*.config' -print | while IFS= read -r cfg_file; do
        update_config_value "$cfg_file" '^clientApp\.0\.args$' "sam.keys ${IP_ADDR} 7656 i2cp.tcp.host=${IP_ADDR} i2cp.tcp.port=7654"
    done
    find "${I2P_DIR_CONFIG}/clients.config.d" -type f -name '*net.i2p.jetty.JettyStart*.config' -print | while IFS= read -r cfg_file; do
        update_config_value "$cfg_file" '^clientApp\.0\.args$' "\"${EEPSITE_DIR}/jetty.xml\""
    done
fi
if [ -d "${I2P_DIR_CONFIG}/i2ptunnel.config.d" ]; then
    find "${I2P_DIR_CONFIG}/i2ptunnel.config.d" -type f -name '*.config' -print | while IFS= read -r cfg_file; do
        update_config_value "$cfg_file" '^interface$' "${IP_ADDR}"
    done
    find "${I2P_DIR_CONFIG}/i2ptunnel.config.d" -type f -name '*I2P webserver-i2ptunnel*.config' -print | while IFS= read -r cfg_file; do
        update_config_value "$cfg_file" '^targetHost$' "${IP_ADDR}"
    done
fi

# Ensure configuration i2psnark.config is updated with env provided parameters.
# This file is the persistent config used when restarting an existing router
if [ -f "${I2P_DIR_CONFIG}/i2psnark.config.d/i2psnark.config" ]; then
    update_config_value "${I2P_DIR_CONFIG}/i2psnark.config.d/i2psnark.config" '^i2psnark\.dir$' "${I2PSNARK_DIR}"
    update_config_value "${I2P_DIR_CONFIG}/i2psnark.config.d/i2psnark.config" '^i2psnark\.i2cpHost$' "${IP_ADDR}"
fi

# Ensure eepsite Jetty listener host follows runtime bind address.
if [ -f "${EEPSITE_DIR}/jetty.xml" ]; then
    update_jetty_xml_set_value "${EEPSITE_DIR}/jetty.xml" 'host' "${IP_ADDR}"
fi

# Ensure I2P installation directory is owned by i2p user
chown -R i2p:i2p "$I2P_DIR_BASE"

# Ensure the configuration directory exists and is owned by the i2p user
mkdir -p "$I2P_DIR_CONFIG" && chown -R i2p:i2p "$I2P_DIR_CONFIG"

# Ensure the i2psnark storage directory exists and is owned by the i2p user
mkdir -p "$I2PSNARK_DIR" && chown -R i2p:i2p "$I2PSNARK_DIR"

# Options required for reflective access in dynamic JVM languages like Groovy and Jython
JAVA17OPTS="--add-opens java.base/java.lang=ALL-UNNAMED \
    --add-opens java.base/sun.nio.fs=ALL-UNNAMED \
    --add-opens java.base/java.nio=ALL-UNNAMED \
    --add-opens java.base/java.util=ALL-UNNAMED"
# Old java options
JAVAOPTS="-Djava.net.preferIPv4Stack=false \
    -Djava.library.path=${I2P_DIR_BASE}:${I2P_DIR_BASE}/lib \
    -Di2p.dir.base=${I2P_DIR_BASE} \
    -Di2p.dir.config=${I2P_DIR_BASE}/.i2p \
    -DloggerFilenameOverride=logs/log-router-@.txt \
    -Xmx$JVM_XMX"

# Follow log files available and mirror them to the container's stdout.
# This does not change how the application writes its logs,
# it simply makes them visible through the container logging system.
# Note: `-DloggerFilenameOverride=logs/log-router-@.txt` above has been
# standard for some time.
follow_log "${I2P_DIR_BASE}/.i2p/wrapper.log" WRAPPER
follow_log "${I2P_DIR_BASE}/.i2p/logs/log-router-0.txt" ROUTER

# Execute the Java application with the constructed classpath and options
# We keep the shell as PID 1 so we can remap I2P router custom exit codes,
# while forwarding standard termination signals to the router process.
log_info "Starting I2P Router"
runuser -u i2p -- java -cp ".:lib/*" ${JAVAOPTS} ${JAVA17OPTS} net.i2p.router.RouterLaunch &
ROUTER_PID=$!

trap 'forward_router_signal TERM' TERM
trap 'forward_router_signal INT' INT
trap 'forward_router_signal HUP' HUP
trap 'forward_router_signal QUIT' QUIT

if wait "$ROUTER_PID"; then
    ROUTER_EXIT=0
else
    ROUTER_EXIT=$?
fi

if [ "$ROUTER_EXIT" -eq 2 ]; then
    log_info "Router exited successfully with graceful shutdown."
    exit 0
fi
if [ "$ROUTER_EXIT" -eq 3 ]; then
    log_info "Router exited successfully with immediate shutdown."
    exit 0
fi

exit "$ROUTER_EXIT"
