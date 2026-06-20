@echo off

REM This launches susimail and jetty in a separate JVM.
REM The file jetty-susimail.xml must be present in the current directory.
REM susimail will be accessed at http://127.0.0.1:8004/

set I2P="."
java -jar "%I2P%/susimail.jar"
