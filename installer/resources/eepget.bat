@echo off

::
:: This should have been changed by the installer
::
set _I2PHOME=%INSTALL_PATH

:: In case the autodetection fails or a specific Java binary is needed,
:: uncomment the next line and set the full path to "java.exe"
::
REM set JAVA=

::
:: If we find Java in the path, or a specific Java binary was set, let's use
:: it instead of figuring it out ourselves.
::
if defined JAVA (
    goto eepget
) else (
    for %%I in (java.exe) do set JAVA=%%~$PATH:I
)
::
:: Does "elif" exist in Windows? It doesn't seem to...
::
if defined JAVA GOTO eepget

::
:: We should only end up here if Java isn't in the path
::
setlocal ENABLEEXTENSIONS
set KEY1="HKLM\SOFTWARE\JavaSoft\Java Runtime Environment"
set KEY2="HKLM\SOFTWARE\Wow6432Node\JavaSoft\Java Runtime Environment"
set VALUE_NAME=CurrentVersion

::
:: The key specified in the KEY1 variable should exist on 32bit windows
:: and 64bit Windows with a 64bit JRE.
::
:: The key specified in KEY2 will be used on a 64bit Windows with a 32-bit JRE.
::
reg query %KEY1% 2>nul && set KEY_NAME=%KEY1% || set KEY_NAME=%KEY2%

::
:: Get the current version from the registry
::
FOR /F "tokens=2,*" %%A IN ('REG QUERY %KEY_NAME% /v %VALUE_NAME% 2^>nul') DO (
    set VersionValue=%%B
)

::
:: Since we didn't find the registry keys (and JAVA wasn't set in this script),
:: we'll quit.
::
if not defined VersionValue (
    @echo Unable to find %KEY_NAME%\%VALUE_NAME% in the registry.
    @echo Please edit this script and manually set the variable JAVA.
    goto end
)

set JAVA_CURRENT=%KEY_NAME%\%VersionValue%
set J_HOME=JavaHome

::
:: Get the Java Home
::
FOR /F "tokens=2,*" %%A IN ('REG QUERY %JAVA_CURRENT% /v %J_HOME% 2^>nul') DO (
    set JAVA_PATH=%%B
)

if not defined JAVA (set JAVA="%JAVA_PATH%\bin\java.exe")

:eepget
::
:: The binary in "%JAVA%" should exist, assuming it was set by us. Perhaps it
:: won't if the user specified it manually. Let's check to be sure.
::
if not exist "%JAVA%" (
    echo. Could not find "%JAVA%". Please ensure that the variable JAVA
    echo. refers to a path that exists.
    goto end
)
"%JAVA%" -cp "%_I2PHOME%\lib\i2p.jar" net.i2p.util.EepGet %1 %2 %3 %4 %5

:end
