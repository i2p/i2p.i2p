@echo off
setlocal

rem Copyright (c) 1999, 2011 Tanuki Software, Ltd.
rem http://www.tanukisoftware.com
rem All rights reserved.
rem
rem This software is the proprietary information of Tanuki Software.
rem You shall use it only in accordance with the terms of the
rem license agreement you entered into with Tanuki Software.
rem http://wrapper.tanukisoftware.com/doc/english/licenseOverview.html
rem
rem Java Service Wrapper general NT service install script
rem

rem -----------------------------------------------------------------------------
rem These settings can be modified to fit the needs of your application
rem Optimized for use with version 3.5.9 of the Wrapper.

rem The base name for the Wrapper binary.
set _WRAPPER_BASE=i2psvc

rem The name and location of the Wrapper configuration file.   This will be used
rem  if the user does not specify a configuration file as the first argument to
rem  this script.
set _WRAPPER_CONF_DEFAULT=.\wrapper.config

rem _PASS_THROUGH tells the script to pass all arguments through to the JVM
rem  as is.
rem set _PASS_THROUGH=true

rem Do not modify anything beyond this point
rem -----------------------------------------------------------------------------

rem
rem Resolve the real path of the wrapper.exe
rem  For non NT systems, the _REALPATH and _WRAPPER_CONF values
rem  can be hard-coded below and the following test removed.
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

rem
rem Decide on the specific Wrapper binary to use (See delta-pack)
rem
if "%PROCESSOR_ARCHITECTURE%"=="AMD64" goto amd64
if "%PROCESSOR_ARCHITECTURE%"=="IA64" goto ia64
set _WRAPPER_L_EXE=%_REALPATH%%_WRAPPER_BASE%-windows-x86-32.exe
goto search
:amd64
set _WRAPPER_L_EXE=%_REALPATH%%_WRAPPER_BASE%-windows-x86-64.exe
goto search
:ia64
set _WRAPPER_L_EXE=%_REALPATH%%_WRAPPER_BASE%-windows-ia-64.exe
goto search
:search
set _WRAPPER_EXE=%_WRAPPER_L_EXE%
if exist "%_WRAPPER_EXE%" goto conf
set _WRAPPER_EXE=%_REALPATH%%_WRAPPER_BASE%.exe
if exist "%_WRAPPER_EXE%" goto conf
echo Unable to locate a Wrapper executable using any of the following names:
echo %_WRAPPER_L_EXE%
echo %_WRAPPER_EXE%
pause
goto :eof

rem
rem Find the wrapper.conf
rem
:conf
set _WRAPPER_CONF="%~f1"
if not [%_WRAPPER_CONF%]==[""] (
    shift
    goto :startup
)
set _WRAPPER_CONF="%_WRAPPER_CONF_DEFAULT%"

:: Add service path to wrapper.config
call "%_REALPATH%"\set_config_dir_for_nt_service.bat install

rem
rem Install the Wrapper as an NT service.
rem
:startup

rem Collect an parameters
:parameters
set _PARAMETERS=%_PARAMETERS% %1
shift
if not [%1]==[] goto :parameters

:: We remove the existing service to
:: 1) force the service to stop
:: 2) update service configuration in case wrapper.config was edited
:: 3) prevent hanging the installer if 'install as service' is selected
::    and it's already enabled as a service.
if [%_PASS_THROUGH%]==[] (
    "%_WRAPPER_EXE%" -r %_WRAPPER_CONF%
    "%_WRAPPER_EXE%" -i %_WRAPPER_CONF%
) else (
    "%_WRAPPER_EXE%" -r %_WRAPPER_CONF% -- %_PARAMETERS%
    "%_WRAPPER_EXE%" -i %_WRAPPER_CONF% -- %_PARAMETERS%
)
if not errorlevel 1 goto :eof
if "%2"=="--nopause" goto :eof
pause

:eof
