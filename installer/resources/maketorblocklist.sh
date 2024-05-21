#!/bin/sh

# Downloads https://check.torproject.org/torbulkexitlist
# using specified http proxy and then creates a consolidated,
# sorted list and removes the downloaded file

# Adapted from I2P+, license granted to I2P under I2P license

set -eu    # abort on error or undefined variable

cd "$(dirname "$0")" # change to resources/installer

# Specify input, output, and proxy variables
input_file="torbulkexitlist"
download_file="$(mktemp)"
trap "rm -fv ""$download_file""" EXIT  # intentional double-quoting
output_file="blocklist-tor.txt"
http_proxy="${http_proxy:-http://127.0.0.1:4444}"

# Variables
url=https://check.torproject.org/"$input_file"
today="$(date '+%d %B %Y')"

# Check if curl is installed using a Bourne-compatible shell built-in
if ! command -v curl > /dev/null 2>&1;  then
  echo "> curl is not installed. Please install curl to use this script." >&2
  exit 1
fi

# Download the latest list from Tor Project
echo " > Downloading the latest list from Tor Project via specified proxy $http_proxy..." >&2
if ! curl --silent -o "$download_file" -x "$http_proxy" "$url" ; then
  echo " > Failed to download the latest list using proxy ${http_proxy}. Please check your proxy settings and try again." >&2
  exit 1
fi

# Count the number of IPs in the file
ips_count=$(wc -l < "$download_file")  # using the redirect prevents the filename from being output
echo " > Number of IPs in $input_file: $ips_count" >&2
echo " > Sorting IPs and consolidating ranges, please stand by..." >&2

# Sort the list of IPs naturally
# The version sort -V will usually do the right thing, but this is explicitly for IP addresses,
# ensuring that each octet is sorted with every version of `sort`.
sorted_ips=$(sort -t . -k1,1n -k2,2n -k3,3n -k4,4n "$download_file")

# Initialize variables
start_ip=""
end_ip=""

[ -r "$output_file" ] && cp "$output_file" "${output_file}".bak

# Print URL and date to the output file
echo "# $url" > "$output_file"
echo "# $today" >> "$output_file"

# Function to print the IP range
print_range() {
  if [ "$start_ip" = "$end_ip" ]; then
    echo "$start_ip" >> "$output_file"
  else
    echo "${start_ip}-${end_ip}" >> "$output_file"
  fi
}

# Function to increment the last octet of an IPv4 IP address
increment_ip() {
  ip="$1"
  a=${ip%.*.*.*}
  b="${ip#*.}"; b="${b%.*.*}"
  c="${ip#*.*.}"; c="${c%.*}"
  d="${ip##*.}"
  d="$((d + 1))"
  if [ "$d" -eq 256 ]; then
    d=0
    c=$((c + 1))
    if [ "$c" -eq 256 ]; then
      c=0
      b=$((b + 1))
      if [ "$b" -eq 256 ]; then
        b=0
        a=$((a + 1))
      fi
    fi
  fi
  echo "$a.$b.$c.$d"
}

# Loop through the sorted IPs to find consecutive IPs
for ip in $sorted_ips; do
  if [ -z "$start_ip" ]; then
    start_ip="$ip"
  elif [ "$ip" != "$(increment_ip "$end_ip")" ]; then
    print_range
    start_ip="$ip"
  fi
  end_ip="$ip"
done

# Print the last range
print_range

echo " > Consolidated IPs saved to: $output_file" >&2
ips_count=$(wc -l < "$output_file")
echo " > Consolidated IP ranges in $output_file: $((ips_count - 2))" >&2
