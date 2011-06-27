@echo off
setlocal

rem
rem Java Service Wrapper general NT service install script
rem

if "%OS%"=="Windows_NT" goto nt
echo This script only works with NT-based versions of Windows.
goto :eof

:nt
rem
rem Find the application home.
rem
rem %~dp0 is location of current script under NT
set _REALPATH=%~dp0
set _WRAPPER_EXE=%_REALPATH%I2Psvc.exe

rem
rem Find the wrapper.conf
rem
:conf
set _WRAPPER_CONF="%~f1"
if not %_WRAPPER_CONF%=="" goto startup
set _WRAPPER_CONF="%_REALPATH%wrapper.config"

rem
rem Install the Wrapper as an NT service.
rem
:startup
:: We remove the existing service to
:: 1) force the service to stop
:: 2) update service configuration in case wrapper.config was edited
:: 3) prevent hanging the installer if 'install as service' is selected 
::    and it's already enabled as a service.
"%_WRAPPER_EXE%" -r %_WRAPPER_CONF%
"%_WRAPPER_EXE%" -i %_WRAPPER_CONF%
if not errorlevel 1 goto :eof
if %2=="--nopause" goto :eof
pause

:eof
