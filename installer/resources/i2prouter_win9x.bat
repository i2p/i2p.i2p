@echo off
setlocal
REM Isn't it great the lengths we go through to launch a task without a dos box?
start javaw -cp lib\i2p.jar net.i2p.util.ShellCommand i2prouter.bat
exit
