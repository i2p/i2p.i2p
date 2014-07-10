Changes may be needed for newer versions than 3.5.25.

Copy Makefile-windows-x86-64.nmake to src\c.

Copy the itoopie icon to src\c\wrapper.ico.

Configure your environment per the apache-ant instructions (i.e., set ANT_HOME
and JAVA_HOME). Then in the wrapper source directory, run build64.bat.

Compiled in VS2010SP1 after creating %USERPROFILE%\.ant.properties with:
vcvars.bat="C:\\Program Files (x86)\\Microsoft Visual Studio 10.0\\VC\\bin\\amd64\\vcvars64.bat".
