#!/bin/sh

# Downloads https://check.torproject.org/torbulkexitlist
# using specified http proxy and then creates a consolidated,
# sorted list and removes the downloaded file

# Adapted from I2P+, license granted to I2P under I2P license

# Specify input, output, and proxy variables
input_file="torbulkexitlist"
output_file="blocklist-tor.txt"
http_proxy=${http_proxy:-"http://127.0.0.1:4444"}

# Variables
url="https://check.torproject.org/$input_file"
today="$(date '+%d %B %Y')"

# Check if curl is installed
if [ -z "$(which curl)" ]; then
  echo "> curl is not installed. Please install curl to use this script."
  exit 1
fi

# Remove existing input file if it exists
if [ -f $input_file ]; then
  rm $input_file
  echo " > Deleted existing local copy of $input_file"
fi

# Download the latest list from Tor Project
echo " > Downloading the latest list from Tor Project via specified proxy $http_proxy..."
curl -o $input_file -x $http_proxy $url 2>/dev/null
if [ $? -ne 0 ]; then
  echo " > Failed to download the latest list using proxy ${http_proxy}. Please check your proxy settings and try again."
  exit 1
fi

# Count the number of IPs in the file
ips_count=$(wc -l $input_file)
echo " > Number of IPs in $input_file: $ips_count"
echo " > Sorting IPs and consolidating ranges, please stand by..."

# Sort the list of IPs naturally
sorted_ips=$(cat $input_file | sort -V)

# Initialize variables
start_ip=""
end_ip=""

cp $output_file ${output_file}.bak

# Print URL and date to the output file
echo "# $url" > $output_file
echo "# $today" >> $output_file

# Function to print the IP range
print_range() {
  if [ "$start_ip" = "$end_ip" ]; then
    echo "$start_ip" >> $output_file
  else
    echo "${start_ip}-${end_ip}" >> $output_file
  fi
}

# Loop through the sorted IPs to find consecutive IPs
for ip in $sorted_ips; do
  if [ "$start_ip" = "" ]; then
    start_ip=$ip
  elif [ "$ip" != $(echo -n "$end_ip" | awk -F. '{ printf "%d.%d.%d.%d", $1, $2, $3, $4 + 1}') ]; then
    print_range
    start_ip=$ip
  fi
  end_ip=$ip
done

# Print the last range
print_range

echo " > Consolidated IPs saved to: $output_file"
ips_count=$(wc -l $output_file)
echo " > Consolidated IP ranges in $output_file: $(($ips_count - 2))"

# Clean up: Remove downloaded input file
rm $input_file
echo " > Deleted $input_file"
