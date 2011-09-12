@echo off
set _I2PHOME=%INSTALL_PATH
java -cp "%_I2PHOME%\lib\i2p.jar" net.i2p.util.EepGet %1 %2 %3 %4 %5
