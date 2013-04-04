/** 
 * Customized I2P launcher.
 * Launches the JRE within the process, to allow Task Manager to show
 * "I2P.exe" as the process, and firewalls to control access of
 * "I2P.exe".
 */

#include "errors.h"

#ifdef _WIN32
#include <windows.h>
#endif

#include <stdio.h>
#include <malloc.h>
#include <sys/stat.h>

//BOOL MoveFontPropertiesFile(const char *path);
#ifdef _WIN32
void SetWorkingDirectory(char *path);
#endif
void readOptions(char***, int*);
//BOOL localJREExists(const char*);
//BOOL exist(const char*);

// defined in java.c
extern void* MemAlloc(size_t);
// defined in java.c
extern int launchJVM(int, char**);


int
main(int argc, char** argv) {

	//int read_options_size;
	//char** read_options;
	int ret = 0;
	//int current_argc = 0;
	//int new_argc;
	//char** new_argv;
	//int i;
#ifdef _WIN32
	char currentDirectory[MAX_PATH+1];
#endif

	// Set/get the correct working directory.
#ifdef _WIN32
	SetWorkingDirectory(currentDirectory);
#endif

	// Read in options from disk (launch.properties)
	// or the default ones (if no launch.properties existed)
	//readOptions(&read_options, &read_options_size);

	// Construct a new argc & argv to pass to launchJVM
	//new_argc = read_options_size + argc;
	//new_argv = (char**)MemAlloc(sizeof(char*) * (new_argc+1));

	// copy process name
	//new_argv[0] = argv[0];
	// copy arguments from properties file
	//for(i = 1; i <= read_options_size; i++)
	//	new_argv[i] = read_options[i-1];
	// copy argv arguments as arguments after the properties file
	// (generally used as arguments for I2P)
	//for(current_argc = 1; current_argc < argc; current_argc++)
	//	new_argv[i++] = argv[current_argc];

	//new_argv[i] = NULL;

	// options are no longer necessary -- free them up.
	//if(read_options != 0)
	//	free(read_options);

    //ret = launchJVM(new_argc, new_argv);
	//free(new_argv);
	ret = launchJVM(argc, argv);
	switch(ret) {
	case ERROR_COULDNT_FIND_JVM:
	case ERROR_COULDNT_INITIALIZE_JVM:
	case ERROR_COULDNT_LOAD_JVM:
#ifdef _WIN32
		if (MessageBox(NULL, "I2P needs the Java Runtime Environment 5.0 or above. Click OK to go to www.java.com, where you can install Java.", 
		       "I2P Launcher Error",
		       MB_ICONWARNING | MB_OKCANCEL) == IDOK)
			ShellExecute(NULL, NULL, "http://www.java.com/", "", "", SW_SHOWNORMAL);
#endif
		break;
	case ERROR_COULDNT_PARSE_ARGUMENTS:
#ifdef _WIN32
		MessageBox(NULL, "I2P failed to parse the commandline arguments to Java.\n"
			"Please download and install I2P again.",
			"I2P Launcher Error", MB_OK);
#endif
		break;
	case ERROR_STARTING_PROGRAM:
#ifdef _WIN32
		MessageBox(NULL, "I2P was unable to load.\n"
				"Please download and install I2P again.",
				"I2P Launcher Error", MB_OK);
#endif
		break;
	}
	return ret;
}



#define MAX_OPTION_SIZE 100
#define MAX_OPTIONS 100

/**
 * Read in the launch.properties file and generate psuedo-commandline arguments.
 */
void readOptions(char*** options, int* size) {
	FILE* file;
	char* buffer;
	int i;
	size_t currentlen;
	char* command;	

	file = fopen("launch.properties", "r");
	if(file == NULL) {
		// default to certain values.
		*size = 10;
#ifdef ALPHA
		*size = (*size) + 1;
#endif
		
		*options = (char**)MemAlloc(sizeof(char*) * (*size));
		i = 0;
		(*options)[i++] = "-Xms64m";
		(*options)[i++] = "-Xmx256m";
		(*options)[i++] = "-Dasdf=fdsa";
		(*options)[i++] = "-Dorg.apache.commons.logging.Log=org.apache.commons.logging.impl.NoOpLog";
		//(*options)[i++] = "-Djava.net.preferIPv6Addresses=false";
		(*options)[i++] = "-Djava.net.preferIPv4Stack=true";
		(*options)[i++] = "-ea:com.limegroup...";
		(*options)[i++] = "-ea:org.limewire...";
		(*options)[i++] = "-Djava.library.path=lib";
		(*options)[i++] = "-jar";
		(*options)[i++] = "lib\\MuWire.jar";
#ifdef ALPHA
#pragma message ("\n\n!!!!!!!!!!!!!! building ALPHA !!!!!!!!!!!!!!\n\n")
		(*options)[(*size) - 3] = "-agentlib:yjpagent=port=11111";
		(*options)[(*size) - 2] = "-jar";
		(*options)[(*size) - 1] = "I2P.jar";
#endif
		return;
	}

	*options = (char**)MemAlloc(sizeof(char*) * MAX_OPTIONS);
	*size = 0;

	for(i = 0; i < MAX_OPTIONS && !feof(file); i++) {
		buffer = (char*)MemAlloc(sizeof(char) * MAX_OPTION_SIZE);
		// get the next option.
		if(fgets(buffer, MAX_OPTION_SIZE - 1, file) == NULL)
			break;
		// strip off the \n if it exists.
		currentlen = strlen(buffer);
		if(currentlen > 0 && buffer[currentlen-1] == '\n') {
			buffer[currentlen-1] = 0;
			currentlen--;
		}
		// downsize the storage for the command.
		currentlen++; // (include \0)
		command = (char*)MemAlloc(sizeof(char) * currentlen);
		strcpy(command, buffer);
		free(buffer);

		// set the option & increase the size
		if(currentlen > 0) {
			(*options)[i] = command;
			(*size)++;
		}
	}

	if(file != NULL)
		fclose(file);
}

/*
 * Sets the current working directory to wherever I2P.exe is located
 */
#ifdef _WIN32
static void
SetWorkingDirectory(char *path) {
	GetModuleFileName(NULL, path, MAX_PATH + 1);
	*strrchr(path, '\\') = '\0'; // remove the .exe
	SetCurrentDirectory(path);
	GetCurrentDirectory(MAX_PATH + 1, path);
}
#endif

/**
 * Checks to see if an app-installed JRE exists.
 */
/*
BOOL localJREExists(const char* path) {
	char localBuf[MAX_PATH + 1];
	sprintf(localBuf, "%s\\jre\\bin\\hotspot\\jvm.dll\0", path);
	return exist(localBuf);
}
*/

/**
 * Copies the windows.font.properties file to the jre so that
 * we use the proper fonts for international installs.
 */
/*
BOOL
MoveFontPropertiesFile(const char *path) {
	char curFontFile[MAX_PATH + 1];
	char newFontFile[MAX_PATH + 1];
	BOOL copySucceeded;
	sprintf(curFontFile, "%s\\windows.font.properties\0", path);
	sprintf(newFontFile, "%s\\jre\\lib\\font.properties\0", path);
	if(!exist(curFontFile))
		return TRUE;

	copySucceeded = CopyFile(curFontFile, newFontFile, FALSE);
	if(copySucceeded)
		DeleteFile(curFontFile);
	return copySucceeded;
}
*/
/*
BOOL exist(const char *filename) {
	struct stat s;
	return stat(filename, &s) == 0 ? TRUE : FALSE;
}
*/

#ifdef _WIN32
__declspec(dllimport) char **__initenv;

int WINAPI
WinMain(HINSTANCE inst, HINSTANCE previnst, LPSTR cmdline, int cmdshow)
{
    int   ret;

    /* It turns out, that Windows can set thread locale to default user locale 
     * instead of default system locale. We correct this by explicitely setting
     * thread locale to system default.
     */
    SetThreadLocale(GetSystemDefaultLCID());

    __initenv = _environ;
    ret = main(__argc, __argv);

    return ret; 
}
#endif
