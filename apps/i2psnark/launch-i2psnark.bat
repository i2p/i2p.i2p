@echo off

REM This launches i2psnark and jetty in a separate JVM.
REM The file jetty-i2psnark.xml must be present in the current directory.
REM i2psnark will be accessed at http://127.0.0.1:8002/

set I2P="."
java -jar "%I2P%/i2psnark.jar"
