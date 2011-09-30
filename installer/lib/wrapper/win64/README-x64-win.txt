Changes will probably be needed for newer versions than 3.5.12.

To use the patch in this directory, copy Makefile-windows-x86-32.nmake to
Makefile-windows-x86-64.nmake then "patch < x64-win.patch".

Configure your environment per the apache-ant instructions (i.e., set ANT_HOME
and JAVA_HOME). Then in the wrapper source directory, run build64.bat.

Compiled in VS2010SP1 after creating %USERPROFILE%\.ant.properties with:
vcvars.bat="C:\\Program Files (x86)\\Microsoft Visual Studio 10.0\\VC\\bin\\amd64\\vcvars64.bat".
