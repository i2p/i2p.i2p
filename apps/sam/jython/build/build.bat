@echo off
rem This makefile constructs the SAM Server i2psam.jar

rem by default, it gets linked into a standalone .jar file, together
rem with the I2P core classes, and the jython classes

CLASSPATH=\path\to\my\jython.jar;..\..\..\..\build\i2p.jar;..\..\..\..\build\mstreaming.jar

@echo Building i2psam.jar
jythonc --jar i2psam.jar --all ..\src\i2psam.py

