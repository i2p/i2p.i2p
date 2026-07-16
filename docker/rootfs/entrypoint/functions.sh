#!/bin/sh

#####################################################################
## Purpose
# This file contains common shell functions used by scripts of the
# docker entrypoint

log() {
    msg="$*"
    printf '%s [ENTRYPOINT] %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$msg"
}
log_info() {
    msg="$*"
    log "INFO: " "$msg"
}
log_warn() {
    msg="$*"
    log "WARN: " "$msg"
}
log_error() {
    msg="$*"
    log "ERROR: " "$msg"
}

##
# @function follow_log
# @description Streams a log file with UTC timestamps and a caller-provided label.
# @details Waits until the log file exists, then tails from the first line in a
# background process and prefixes each emitted line with timestamp and label.
# @param $1 Path to the log file to follow.
# @param $2 Label included in each emitted line.
# @returns 0 when the background follower is started.
follow_log() {
    logfile="$1"
    label="$2"
    sh -c '
        logfile="$1"
        label="$2"
        while [ ! -f "$logfile" ]; do sleep 0.5; done
        tail -n +1 -F "$logfile" 2>/dev/null | while IFS= read -r line; do
            printf "%s [%s] %s\n" "$(date -u "+%Y-%m-%dT%H:%M:%SZ")" "$label" "$line"
        done
    ' sh "$logfile" "$label" &
}

##
# @function forward_router_signal
# @description Forwards a signal to the router child process when it is still running.
# @details Expects ROUTER_PID to be set by the caller script.
# @param $1 Signal name (for example TERM, INT, HUP, QUIT).
# @returns 0 when handled.
forward_router_signal() {
    sig="$1"
    if kill -0 "$ROUTER_PID" 2>/dev/null; then
        kill -"$sig" "$ROUTER_PID" 2>/dev/null || true
    fi
}

##
# @function has_ipv4_connectivity
# @description Returns success when at least one non-loopback global IPv4 address
# is configured on an interface that is up.
# @returns 0 if a global IPv4 address is present, otherwise 1.
has_ipv4_connectivity() {
    ip -4 -o addr show up scope global 2>/dev/null | grep -q 'inet '
}

##
# @function has_ipv6_connectivity
# @description Returns success when IPv6 is enabled and at least one non-loopback
# global IPv6 address is configured on an interface that is up.
# @details If /proc/sys/net/ipv6/conf/all/disable_ipv6 is 1, this returns failure.
# @returns 0 if global IPv6 connectivity is available, otherwise 1.
has_ipv6_connectivity() {
    if [ -r /proc/sys/net/ipv6/conf/all/disable_ipv6 ] && \
       [ "$(cat /proc/sys/net/ipv6/conf/all/disable_ipv6 2>/dev/null)" = "1" ]; then
        return 1
    fi
    ip -6 -o addr show up scope global 2>/dev/null | grep -q 'inet6 '
}

##
# @function has_host_network_mode
# @description Returns success when interface names indicate Docker host/network bridge plumbing.
# @details This keeps the existing heuristic based on docker, br-, or veth link names.
# @returns 0 when host-network indicators are present, otherwise 1.
has_host_network() {
    ip link show 2>/dev/null | grep -qE 'docker|br-|veth'
}

##
# @function get_last_hostname_ipv4
# @description Prints the last IPv4 token returned by `hostname -i`.
# @details Compatibility helper only; `hostname -i` output ordering is not guaranteed.
# @returns 0 and prints an IPv4 address when found, otherwise 1.
get_last_hostname_ipv4() {
    hostname -i 2>/dev/null | awk '
        {
            for (i = 1; i <= NF; i++) {
                if ($i ~ /^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$/) {
                    ip = $i
                }
            }
        }
        END {
            if (ip != "") {
                print ip
                exit 0
            }
            exit 1
        }
    '
}

##
# @function get_default_route_ipv4
# @description Prints the IPv4 source address chosen by kernel routing.
# @details Uses `ip -4 route get 1.1.1.1` and extracts the `src` value.
# @returns 0 and prints an IPv4 address when found, otherwise 1.
get_default_route_ipv4() {
    ip -4 route get 1.1.1.1 2>/dev/null | awk '
        {
            for (i = 1; i <= NF; i++) {
                if ($i == "src" && (i + 1) <= NF) {
                    print $(i + 1)
                    found = 1
                    exit
                }
            }
        }
        END {
            if (!found) {
                exit 1
            }
        }
    '
}

##
# @function get_private_bind_ipv4
# @description Picks a conservative IPv4 bind address for containerized services.
# @details In host network mode, returns 127.0.0.1 to keep services local.
# In bridged mode, prefers the default-route source address.
# Falls back to the last IPv4 from `hostname -i`, then 127.0.0.1.
# @returns 0 and prints the selected IPv4 address.
get_private_bind_ipv4() {
    selected_ipv4=""

    if has_host_network; then
        printf '%s\n' "127.0.0.1"
        return 0
    fi

    if selected_ipv4="$(get_default_route_ipv4)" && [ -n "$selected_ipv4" ]; then
        case "$selected_ipv4" in
            127.*) ;;
            *)
                printf '%s\n' "$selected_ipv4"
                return 0
                ;;
        esac
    fi

    if selected_ipv4="$(get_last_hostname_ipv4)" && [ -n "$selected_ipv4" ]; then
        case "$selected_ipv4" in
            127.*) ;;
            *)
                printf '%s\n' "$selected_ipv4"
                return 0
                ;;
        esac
    fi

    printf '%s\n' "127.0.0.1"
}

##
# @function update_config_value
# @description Updates matching key/value entries in a config file.
# @details Only non-comment lines with `key=value` format are considered. A line is
# rewritten when the key matches `key_regex` and the existing value matches the
# optional `current_value_regex` (defaults to matching any value).
# @param $1 Path to the config file.
# @param $2 Regular expression for the key (left side of `=`).
# @param $3 New value to write (right side of `=`).
# @param $4 Optional regular expression for current value filtering.
# @returns 0 on success, otherwise 1.
update_config_value() {
    # Usage:
    #   update_config_value <config_file> <key_regex> <new_value> [current_value_regex]
    #
    # Examples:
    #   update_config_value "/path/file.conf" '^foo\.bar$' '10.0.0.1'
    #   update_config_value "/path/file.conf" '^foo\.bar$' '10.0.0.1' '^127\.0\.0\.1$'

    if [ $# -lt 3 ] || [ $# -gt 4 ]; then
        log_error "Usage: update_config_value <config_file> <key_regex> <new_value> [current_value_regex]"
        return 1
    fi

    cfg="$1"
    key_re="$2"
    new_value="$3"
    current_re="${4:-.*}"   # default: match any current value

    if [ ! -f "$cfg" ]; then
        log_error "Config file not found: $cfg"
        return 1
    fi

    awk_bin="$(command -v awk)"
    if [ -z "$awk_bin" ]; then
        log_error "awk is required for update_config_value"
        return 1
    fi

    if ! tmpfile="$(mktemp "${cfg}.tmp.XXXXXX")"; then
        log_error "Failed to create temporary file for $cfg"
        return 1
    fi

    if "$awk_bin" -v key_re="$key_re" -v new_value="$new_value" -v current_re="$current_re" -v debug_entrypoint="${DEBUG_ENTRYPOINT:-}" '
        BEGIN { updated = 0 }

        # Keep comments untouched
        /^[[:space:]]*#/ { print; next }

        {
            eq = index($0, "=")
            if (eq > 0) {
                key = substr($0, 1, eq - 1)
                val = substr($0, eq + 1)

                # Update only matching key, and only matching current value (if provided)
                if (key ~ key_re && val ~ current_re) {
                    print key "=" new_value
                    updated++
                    next
                }
            }
            print
        }

        END {
            if (debug_entrypoint != "") {
                printf "update_config_value: updated %d line(s)\n", updated > "/dev/stderr"
            }
        }
    ' "$cfg" > "$tmpfile"; then
        if ! mv "$tmpfile" "$cfg"; then
            log_error "Failed to replace config file: $cfg"
            rm -f "$tmpfile"
            return 1
        fi
    else
        log_error "awk failed while updating '$cfg' with key regex '$key_re'"
        rm -f "$tmpfile"
        return 1
    fi
}

##
# @function update_jetty_xml_set_value
# @description Updates XML lines in the form `<Set name="...">value</Set>`.
# @details A line is rewritten when the Set name equals `set_name` and the
# existing inner value matches the optional `current_value_regex` (defaults to
# matching any value). The helper is intended for simple single-line Set tags.
# @param $1 Path to the XML file.
# @param $2 Set name to match (for example `host`).
# @param $3 New inner value to write.
# @param $4 Optional regular expression for current value filtering.
# @returns 0 on success, otherwise 1.
update_jetty_xml_set_value() {
    # Usage:
    #   update_jetty_xml_set_value <xml_file> <set_name> <new_value> [current_value_regex]
    #
    # Examples:
    #   update_jetty_xml_set_value "/path/jetty.xml" 'host' '0.0.0.0'
    #   update_jetty_xml_set_value "/path/jetty.xml" 'host' '0.0.0.0' '^127\.0\.0\.1$'

    if [ $# -lt 3 ] || [ $# -gt 4 ]; then
        log_error "Usage: update_jetty_xml_set_value <xml_file> <set_name> <new_value> [current_value_regex]"
        return 1
    fi

    xml_file="$1"
    set_name="$2"
    new_value="$3"
    current_re="${4:-.*}"

    if [ ! -f "$xml_file" ]; then
        log_error "XML file not found: $xml_file"
        return 1
    fi

    awk_bin="$(command -v awk)"
    if [ -z "$awk_bin" ]; then
        log_error "awk is required for update_jetty_xml_set_value"
        return 1
    fi

    if ! tmpfile="$(mktemp "${xml_file}.tmp.XXXXXX")"; then
        log_error "Failed to create temporary file for $xml_file"
        return 1
    fi

    if "$awk_bin" -v set_name="$set_name" -v new_value="$new_value" -v current_re="$current_re" -v debug_entrypoint="${DEBUG_ENTRYPOINT:-}" '
        BEGIN { updated = 0 }

        {
            # Match simple, single-line Set tags: <Set name="host">value</Set>
            line_re = "^[[:space:]]*<Set[[:space:]]+name=\"" set_name "\">[^<]*</Set>[[:space:]]*$"
            if ($0 ~ line_re) {
                value = $0
                sub("^[[:space:]]*<Set[[:space:]]+name=\"" set_name "\">", "", value)
                sub("</Set>[[:space:]]*$", "", value)

                if (value ~ current_re) {
                    sub(/>[^<]*</, ">" new_value "<")
                    updated++
                }
            }
            print
        }

        END {
            if (debug_entrypoint != "") {
                printf "update_jetty_xml_set_value: updated %d line(s)\n", updated > "/dev/stderr"
            }
        }
    ' "$xml_file" > "$tmpfile"; then
        if ! mv "$tmpfile" "$xml_file"; then
            log_error "Failed to replace XML file: $xml_file"
            rm -f "$tmpfile"
            return 1
        fi
    else
        log_error "awk failed while updating '$xml_file' for Set name '$set_name'"
        rm -f "$tmpfile"
        return 1
    fi
}

