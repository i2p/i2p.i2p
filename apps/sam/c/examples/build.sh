#!/bin/sh

rm dgram-server.o dgram-server
gcc -I../inc -o dgram-server.o -c dgram-server.c
gcc -I../inc -L../lib -o dgram-server dgram-server.o -lsam
rm dgram-client.o dgram-client
gcc -I../inc -o dgram-client.o -c dgram-client.c
gcc -I../inc -L../lib -o dgram-client dgram-client.o -lsam
