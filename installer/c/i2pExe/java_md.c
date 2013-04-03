/**
 * Derived from the shared source for the 'java' commandline tool.
 */

/* Backported from Tiger (1.5) java_md.c	1.40 04/01/12 */
/* AMD64 support removed */

#include <windows.h>
#include <process.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <sys/types.h>
#include <sys/stat.h>

#include "jni.h"
#include "java.h"

#define JVM_DLL "jvm.dll"
#define JAVA_DLL "java.dll"

/*
 * Prototypes.
 */
static jboolean GetPublicJREHome(char *path, jint pathsize);
static jboolean GetJVMPath(const char *jrepath, const char *jvmtype,
			   char *jvmpath, jint jvmpathsize);
static jboolean GetJREPath(char *path, jint pathsize);

const char *
GetArch()
{
#ifdef _WIN64
    return "ia64";
#else
    return "i386";
#endif
}

/*
 *
 */
int
CreateExecutionEnvironment(int *_argc,
			   char ***_argv,
			   char jrepath[],
			   jint so_jrepath,
			   char jvmpath[],
			   jint so_jvmpath,
			   char **original_argv) {
   char * jvmtype;

    /* Find out where the JRE is that we will be using. */
    if (!GetJREPath(jrepath, so_jrepath)) {
	ReportErrorMessage("Could not find a suitable Java 2 Runtime Environment.",
			   JNI_TRUE);
		return 1;
    }

    /* Find the specified JVM type */
    if (ReadKnownVMs(jrepath, (char*)GetArch(), JNI_FALSE) < 1) {
	ReportErrorMessage("Error: no known VMs. (check for corrupt jvm.cfg file)", 
			   JNI_TRUE);
		return 1;
    }
    jvmtype = CheckJvmType(_argc, _argv, JNI_FALSE);

    jvmpath[0] = '\0';
    if (!GetJVMPath(jrepath, jvmtype, jvmpath, so_jvmpath)) {
        char * message=NULL;
	const char * format = "Error: no `%s' JVM at `%s'.";
	message = (char *)MemAlloc((strlen(format)+strlen(jvmtype)+
				    strlen(jvmpath)) * sizeof(char));
	sprintf(message,format, jvmtype, jvmpath); 
	ReportErrorMessage(message, JNI_TRUE);
		return 1;
    }
    /* If we got here, jvmpath has been correctly initialized. */
	return 0;

}

/*
 * Find path to JRE based on .exe's location or registry settings.
 */
jboolean
GetJREPath(char *path, jint pathsize)
{
    char javadll[MAXPATHLEN];
    struct stat s;
//
//    if (GetApplicationHome(path, pathsize)) {
//		/* Is JRE co-located with the application? */
//		sprintf(javadll, "%s\\bin\\" JAVA_DLL, path);
//		if (stat(javadll, &s) == 0) {
//			goto found;
//		}

//		/* Does this app ship a private JRE in <apphome>\jre directory? */
//		sprintf(javadll, "%s\\jre\\bin\\" JAVA_DLL, path);
//		if (stat(javadll, &s) == 0) {
//			strcat(path, "\\jre");
//			goto found;
//		}
//    }

    /* Look for a public JRE on this machine. */
    if (GetPublicJREHome(path, pathsize))
		goto found;

    fprintf(stderr, "Error: could not find " JAVA_DLL "\n");
    return JNI_FALSE;

 found:
	
	{
		// trick into thinking that the jre/bin path is in the PATH
		char *oldPath = getenv("PATH");
		char *newPath;
		if(oldPath != NULL) {
			newPath = MemAlloc(strlen(oldPath) + strlen(path) + 6 + 6 + 1); // PATH=<new>\bin;<old>
			sprintf(newPath, "PATH=%s\\bin;%s", path, oldPath);
		} else {
			newPath = MemAlloc(strlen(path) + 6 + 1);
			sprintf(newPath, "PATH=%s\\bin", path);
		}
		_putenv(newPath);
		free(newPath);
	}

    return JNI_TRUE;
}

/*
 * Given a JRE location and a JVM type, construct what the name the
 * JVM shared library will be.  Return true, if such a library
 * exists, false otherwise.
 */
static jboolean
GetJVMPath(const char *jrepath, const char *jvmtype,
	   char *jvmpath, jint jvmpathsize)
{
    struct stat s;
    if (strchr(jvmtype, '/') || strchr(jvmtype, '\\')) {
	sprintf(jvmpath, "%s\\" JVM_DLL, jvmtype);
    } else {
	sprintf(jvmpath, "%s\\bin\\%s\\" JVM_DLL, jrepath, jvmtype);
    }
    if (stat(jvmpath, &s) == 0) {
	return JNI_TRUE;
    } else {
	return JNI_FALSE;
    }
}

/*
 * Load a jvm from "jvmpath" and initialize the invocation functions.
 */
jboolean
LoadJavaVM(const char *jvmpath, InvocationFunctions *ifn)
{
    HINSTANCE handle;

    /* Load the Java VM DLL */
    if ((handle = LoadLibrary(jvmpath)) == 0) {
	ReportErrorMessage2("Error loading: %s", (char *)jvmpath, JNI_TRUE);
	return JNI_FALSE;
    }

    /* Now get the function addresses */
    ifn->CreateJavaVM =
	(void *)GetProcAddress(handle, "JNI_CreateJavaVM");
    ifn->GetDefaultJavaVMInitArgs =
	(void *)GetProcAddress(handle, "JNI_GetDefaultJavaVMInitArgs");
    if (ifn->CreateJavaVM == 0 || ifn->GetDefaultJavaVMInitArgs == 0) {
	ReportErrorMessage2("Error: can't find JNI interfaces in: %s", 
			    (char *)jvmpath, JNI_TRUE);
	return JNI_FALSE;
    }

    return JNI_TRUE;
}


/*
 * If app is "c:\foo\bin\javac", then put "c:\foo" into buf.
 */
jboolean
GetApplicationHome(char *buf, jint bufsize)
{
    char *cp;
    GetModuleFileName(0, buf, bufsize);
    *strrchr(buf, '\\') = '\0'; /* remove .exe file name */
    if ((cp = strrchr(buf, '\\')) == 0) {
	/* This happens if the application is in a drive root, and
	 * there is no bin directory. */
	buf[0] = '\0';
	return JNI_FALSE;
    }
	if(strcmp(cp, "\\bin") == 0)
		*cp = '\0';  /* remove the bin\ part */
	// else c:\foo\something\javac
    return JNI_TRUE;
}

/*
 * Helpers to look in the registry for a public JRE.
 */
                    /* Same for 1.5.0, 1.5.1, 1.5.2 etc. */
#define DOTRELEASE  JDK_MAJOR_VERSION "." JDK_MINOR_VERSION
#define JRE_KEY	    "Software\\JavaSoft\\Java Runtime Environment"

static jboolean
GetStringFromRegistry(HKEY key, const char *name, char *buf, jint bufsize)
{
    DWORD type, size;

    if (RegQueryValueEx(key, name, 0, &type, 0, &size) == 0
	&& type == REG_SZ
	&& (size < (unsigned int)bufsize)) {
	if (RegQueryValueEx(key, name, 0, 0, buf, &size) == 0) {
	    return JNI_TRUE;
	}
    }
    return JNI_FALSE;
}

static BOOL versionOk(char *version)
{
	char *major;
	int major_version;
	int minor_version;
	char *minor_start;
	// 1. find the .
	minor_start = strstr(version,".");
	if (minor_start == NULL) // no . found.
		return FALSE;
	
	major = (char *)malloc(minor_start - version + 1);
	strncpy(major,version,minor_start - version);
	major[minor_start - version]='\0';
	major_version = atoi(major);
	free(major);

	if (minor_start[1] == '\0') // "x." ?
		return FALSE;

	// anything less than 1.6 is not supported
	minor_version = atoi(++minor_start);
	if (major_version <1 || (major_version < 2 && minor_version < 6))
		return FALSE;

	return TRUE;
}

static jboolean
GetPublicJREHome(char *buf, jint bufsize)
{
    HKEY key, subkey;
    char version[MAXPATHLEN];

    /* Find the current version of the JRE */
    if (RegOpenKeyEx(HKEY_LOCAL_MACHINE, JRE_KEY, 0, KEY_READ, &key) != 0) {
	fprintf(stderr, "Error opening registry key '" JRE_KEY "'\n");
	return JNI_FALSE;
    }

    if (!GetStringFromRegistry(key, "CurrentVersion",
			       version, sizeof(version))) {
	fprintf(stderr, "Failed reading value of registry key:\n\t"
		JRE_KEY "\\CurrentVersion\n");
	RegCloseKey(key);
	return JNI_FALSE;
    }

    /* Check if the version is good */
	if (!versionOk(version))
	{
		RegCloseKey(key);
		return JNI_FALSE;
	}

    /* Find directory where the current version is installed. */
    if (RegOpenKeyEx(key, version, 0, KEY_READ, &subkey) != 0) {
	fprintf(stderr, "Error opening registry key '"
		JRE_KEY "\\%s'\n", version);
	RegCloseKey(key);
	return JNI_FALSE;
    }

    if (!GetStringFromRegistry(subkey, "JavaHome", buf, bufsize)) {
	fprintf(stderr, "Failed reading value of registry key:\n\t"
		JRE_KEY "\\%s\\JavaHome\n", version);
	RegCloseKey(key);
	RegCloseKey(subkey);
	return JNI_FALSE;
    }

    RegCloseKey(key);
    RegCloseKey(subkey);
    return JNI_TRUE;
}


void ReportErrorMessage(char * message, jboolean always) {
  if (message != NULL) {
   // MessageBox(NULL, message, "Java Virtual Machine Launcher",
	//       (MB_OK|MB_ICONSTOP|MB_APPLMODAL)); 
  }
}

void ReportErrorMessage2(char * format, char * string, jboolean always) { 
  /*
   * The format argument must be a printf format string with one %s
   * argument, which is passed the string argument.
   */
  size_t size;
  char * message;
  size = strlen(format) + strlen(string);
  message = (char*)MemAlloc(size*sizeof(char));
  sprintf(message, (const char *)format, string);
  
  if (message != NULL) {
   // MessageBox(NULL, message, "Java Virtual Machine Launcher",
	//       (MB_OK|MB_ICONSTOP|MB_APPLMODAL)); 
  }
}

void  ReportExceptionDescription(JNIEnv * env) {
  /*
   * This code should be replaced by code which opens a window with
   * the exception detail message.
   */
  (*env)->ExceptionDescribe(env);
}


/*
 * Return JNI_TRUE for an option string that has no effect but should
 * _not_ be passed on to the vm; return JNI_FALSE otherwise. On
 * windows, there are no options that should be screened in this
 * manner.
 */
jboolean RemovableMachineDependentOption(char * option) {
  return JNI_FALSE;
}

void PrintMachineDependentOptions() {
  return;
}

jboolean
ServerClassMachine() {
  jboolean result = JNI_FALSE;
  return result;
}


/*
 * Wrapper for platform dependent unsetenv function.
 */
int
UnsetEnv(char *name)
{
    int ret;
    char *buf = MemAlloc(strlen(name) + 2);
    buf = strcat(strcpy(buf, name), "=");
    ret = _putenv(buf);
    free(buf);
    return (ret);
}

/*
 * The following just map the common UNIX name to the Windows API name,
 * so that the common UNIX name can be used in shared code.
 */
int
strcasecmp(const char *s1, const char *s2)
{
    return (stricmp(s1, s2));
}

int
strncasecmp(const char *s1, const char *s2, size_t n)
{
    return (strnicmp(s1, s2, n));
}
