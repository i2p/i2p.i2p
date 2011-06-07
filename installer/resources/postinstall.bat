:: I2P Installer - Installs and pre-configures I2P.
::
:: postinstall.bat
:: 2004 The I2P Project
:: http://www.i2p2.de/
:: This code is public domain.
::
:: author: hypercubus
::
:: Installs the Java Service Wrapper support files for Win32 then launches the
:: I2P router as a background service.

@echo off
setlocal

if "%OS%"=="Windows_NT" (
set INSTALL_PATH=%~dp0
) else (
set INSTALL_PATH="%1"
)

copy "%INSTALL_PATH%lib\wrapper\win32\I2Psvc.exe" "%INSTALL_PATH%"
copy "%INSTALL_PATH%lib\wrapper\win32\wrapper.dll" "%INSTALL_PATH%lib"
copy "%INSTALL_PATH%lib\wrapper\all\wrapper.jar" "%INSTALL_PATH%lib"

if "%OS%"=="Windows_NT" (

del /f /q "%INSTALL_PATH%i2prouter"
:: del /f /q "%INSTALL_PATH%install_i2p_service_unix"
del /f /q "%INSTALL_PATH%install-headless.txt"
del /f /q "%INSTALL_PATH%osid"
del /f /q "%INSTALL_PATH%postinstall.sh"
del /f /q "%INSTALL_PATH%startRouter.sh"
:: del /f /q "%INSTALL_PATH%uninstall_i2p_service_unix"
del /f /q "%INSTALL_PATH%icons\*.xpm"
rmdir /q /s "%INSTALL_PATH%lib\wrapper"
start /b /i /d"%INSTALL_PATH%" i2prouter.bat %INSTALL_PATH%

) else (

del "%INSTALL_PATH%eepget"
del "%INSTALL_PATH%i2prouter"
:: del "%INSTALL_PATH%install_i2p_service_unix"
del "%INSTALL_PATH%install_i2p_service_winnt.bat"
del "%INSTALL_PATH%install-headless.txt"
del "%INSTALL_PATH%osid"
del "%INSTALL_PATH%postinstall.sh"
del "%INSTALL_PATH%startRouter.sh"
:: del "%INSTALL_PATH%uninstall_i2p_service_unix"
del "%INSTALL_PATH%uninstall_i2p_service_winnt.bat"
del "%INSTALL_PATH%icons\*.xpm"
deltree /Y "%INSTALL_PATH%lib\wrapper"
start /M "%INSTALL_PATH%i2prouter.bat" %INSTALL_PATH%

)
