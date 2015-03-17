Bundled in ../java/lib/ are the  binaries for systray4j version 2.4.1 2004-03-28,
which is still the latest.

Files are from systray4j-2.4.1-win32.zip.

SHA1Sums:
	28acaea97816f53d188d01fd88b72e670e67286b  systray4j-2.4.1-win32.zip
	a7f5e02c3652f3f1a72559e54ee69226b8b97859  systray4j.dll
	947bd91c483494256cf48ad87c211e8701b4f85b  systray4j.jar


systray4j.dll is LGPLv2.1, see ../../../licenses/LICENSE-LGPLv2.1.txt
systray4j.jar for Windows is LGPLv2.1, see ../../../licenses/LICENSE-LGPLv2.1.txt
Reference: http://systray.sourceforge.net/
I2P systray code in ../java/src is public domain.

SysTray is really obsolete. It supports Windows and kde3 only.
We only instantiate it on Windows.

The java.awt.SystemTray classes added in Java 6
(and used by apps/desktopgui) are the way to go now.

We could either rewrite this to use SystemTray, or switch to desktopgui.
