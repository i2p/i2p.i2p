:: I2P Installer - Installs and pre-configures I2P.
::
:: postinstall.bat
:: 2004 The I2P Project
:: http://www.i2p.net
:: This code is public domain.
::
:: author: hypercubus
::
:: Installs the Java Service Wrapper support files for Win32 then launches the
:: I2P router as a background service.

@echo off
setlocal
set INSTALL_PATH=%~dp0
copy "%INSTALL_PATH%lib\wrapper\win32\I2Psvc.exe" "%INSTALL_PATH%"
copy "%INSTALL_PATH%lib\wrapper\win32\wrapper.dll" "%INSTALL_PATH%lib"
copy "%INSTALL_PATH%lib\wrapper\win32\wrapper.jar" "%INSTALL_PATH%lib"
del /f /q "%INSTALL_PATH%i2prouter"
:: del /f /q "%INSTALL_PATH%install_i2p_service_unix"
del /f /q "%INSTALL_PATH%install-headless.txt"
del /f /q "%INSTALL_PATH%osid"
del /f /q "%INSTALL_PATH%postinstall.sh"
:: del /f /q "%INSTALL_PATH%uninstall_i2p_service_unix"
del /f /q "%INSTALL_PATH%icons\*.xpm"
rmdir /q /s "%INSTALL_PATH%lib\wrapper"
start /b /i /d"%INSTALL_PATH%" i2prouter.bat
