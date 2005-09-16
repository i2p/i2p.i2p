/*

 	launch4j :: Cross-platform Java application wrapper for creating Windows native executables
	Copyright (C) 2004-2005 Grzegorz Kowal

	Compiled with Mingw port of GCC, Bloodshed Dev-C++ IDE (http://www.bloodshed.net/devcpp.html)

	This library is free software; you can redistribute it and/or
	modify it under the terms of the GNU Lesser General Public
	License as published by the Free Software Foundation; either
	version 2.1 of the License, or (at your option) any later version.

	This library is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
	Lesser General Public License for more details.

	You should have received a copy of the GNU Lesser General Public
	License along with this library; if not, write to the Free Software
	Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

*/

// ICON
#define APP_ICON 1

// BITMAP
#define SPLASH_BITMAP 1

// RCDATA
#define JRE_PATH 1
#define JAVA_MIN_VER 2
#define JAVA_MAX_VER 3
#define SHOW_SPLASH 4
#define SPLASH_WAITS_FOR_WINDOW 5
#define SPLASH_TIMEOUT 6
#define SPLASH_TIMEOUT_ERR 7
#define CHDIR 8
#define SET_PROC_NAME 9
#define ERR_TITLE 10
#define GUI_HEADER_STAYS_ALIVE 11
#define JVM_ARGS 12
#define JAR_ARGS 13
