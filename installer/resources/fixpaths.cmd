@echo off
:: fixpaths.cmd
::
:: This is a simple (and/or stupid) script whose sole purpose is to set the
:: correct path for wrapper.logfile, by explicitly setting it to use the
:: environment variable %temp%.
::
:: On every *NIX-like system, $SYSTEM_java_io_tmpdir/wrapper.log points to a system-level
:: temp directory (/tmp on Linux, /var/tmp on BSD, etc.), but in Windows the value of %temp%
:: depends on whose account a process is running under. If the same user that installs I2P
:: is the only one that will run I2P, this isn't a problem.
::
:: The problem comes from trying to run the process as a service, or trying to run under an
:: account other than the one that did the installation. For example if the user "Administrator"
:: installed I2P on Windows 7, the value for wrapper.logfile will be set to the hardcoded value of
:: C:\Users\Administrator\AppData\Local\Temp\wrapper.log (if it's left at the default value of
:: $SYSTEM_java_io_tmpdir/wrapper.log.
::
:: If user Alice tries to run I2P, the wrapper will try to write its logfile to
:: C:\Users\Administrator\AppData\Local\Temp\wrapper.log. Unfortunately Alice
:: doesn't have the rights to access Administrator's temp directory. The same
:: will happen with the "limited access account" that the I2P service runs
:: under.
::
:: Since Windows doesn't have sed and it has a retarded find, we resort to this
:: lameness.
::
cd /d %~dp0
findstr /V /R "^wrapper.logfile=" wrapper.config > wrapper.tmp1
findstr /V /R "^wrapper.java.pidfile=" wrapper.tmp1 > wrapper.tmp2
findstr /V /R "^wrapper.pidfile=" wrapper.tmp2 > wrapper.new
del /F /Q wrapper.tmp*
echo wrapper.logfile=%%temp%%\wrapper.log >> wrapper.new
echo wrapper.java.pidfile=%%temp%%\routerjvm.pid >> wrapper.new
echo wrapper.pidfile=%%temp%%\i2p.pid >> wrapper.new
move /Y wrapper.new wrapper.config
