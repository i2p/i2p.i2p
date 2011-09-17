@echo off
rem %~dp0 is location of current script under NT
set _REALPATH=%~dp0
set _WRAPPER_CONF="%_REALPATH%wrapper.config"

cd /d %~dp0
if "%1"=="uninstall" (
    FINDSTR /I /v "^wrapper.java.additional.5=-Di2p.dir.config=" %_WRAPPER_CONF% >> %_WRAPPER_CONF%.new
    move %_WRAPPER_CONF%.new %_WRAPPER_CONF% >nul
    goto end
) else (
    FINDSTR /I "^wrapper.java.additional.5=-Di2p.dir.config=" %_WRAPPER_CONF%
    if not errorlevel 1 goto end
    echo wrapper.java.additional.5=-Di2p.dir.config="%ALLUSERSPROFILE%\Application Data\i2p" >> %_WRAPPER_CONF%
    goto end
)

:end
