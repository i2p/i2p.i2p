@echo off
rem This makefile constructs the SAM Server i2psam.jar
rem Once you've built i2psam.jar, you'll need to copy it and
rem jython.jar into the main jars directory

@echo Ensuring i2p jars are built
cd ..\..\..\..
ant build
cd apps\sam\jython\build

@echo Building i2psam.jar
@echo on
set CLASSPATH=..\..\..\..\build\i2p.jar;..\..\..\..\build\mstreaming.jar

@echo Building i2psam.jar
jythonc --jar i2psam.jar ..\src\i2psam.py
