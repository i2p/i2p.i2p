@echo off
setlocal
set INSTALL_PATH="%1"

rem
rem Java Service Wrapper general startup script
rem

set _WRAPPER_EXE=%INSTALL_PATH%I2Psvc.exe
set _WRAPPER_CONF="%INSTALL_PATH%wrapper.config"

"%_WRAPPER_EXE%" -c %_WRAPPER_CONF%
if not errorlevel 1 goto :eof
pause

