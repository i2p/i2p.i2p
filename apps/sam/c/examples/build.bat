@echo off

del dgram-server.o dgram-server.exe
C:\Dev-Cpp\bin\gcc -I../inc -o dgram-server.o -c dgram-server.c
C:\Dev-Cpp\bin\gcc -I../inc -L../lib -o dgram-server.exe dgram-server.o -lsam -lwsock32
del dgram-client.o dgram-client.exe
C:\Dev-Cpp\bin\gcc -I../inc -o dgram-client.o -c dgram-client.c
C:\Dev-Cpp\bin\gcc -I../inc -L../lib -o dgram-client.exe dgram-client.o -lsam -lwsock32