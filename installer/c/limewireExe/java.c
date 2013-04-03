/*
 * Derived from the shared source for the 'java' command line tool.
 *
 * Original comments as follows:
 * Shared source for 'java' command line tool.
 *
 * If JAVA_ARGS is defined, then acts as a launcher for applications. For
 * instance, the JDK command line tools such as javac and javadoc (see
 * makefiles for more details) are built with this program.  Any arguments
 * prefixed with '-J' will be passed directly to the 'java' command.
 */

/*
 * One job of the launcher is to remove command line options which the
 * vm does not understand and will not process.  These options include
 * options which select which style of vm is run (e.g. -client and
 * -server) as well as options which select the data model to use.
 * Additionally, for tools which invoke an underlying vm "-J-foo"
 * options are turned into "-foo" options to the vm.  This option
 * filtering is handled in a number of places in the launcher, some of
 * it in machine-dependent code.  In this file, the function
 * CheckJVMType removes vm style options and TranslateDashJArgs
 * removes "-J" prefixes.  On unix platforms, the
 * CreateExecutionEnvironment function from the unix java_md.c file
 * processes and removes -d<n> options.  However, in case
 * CreateExecutionEnvironment does not need to exec because
 * LD_LIBRARY_PATH is set acceptably and the data model does not need
 * to be changed, ParseArguments will screen out the redundant -d<n>
 * options and prevent them from being passed to the vm; this is done
 * by using the machine-dependent call
 * RemovableMachineDependentOption.
 */

#include "errors.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

#include "jni.h"
#include "java.h"

#ifndef FULL_VERSION
#define FULL_VERSION JDK_MAJOR_VERSION "." JDK_MINOR_VERSION
#endif


static jboolean printVersion = JNI_FALSE; /* print and exit */
static jboolean showVersion = JNI_FALSE;  /* print but continue */

/*
 * List of VM options to be specified when the VM is created.
 */
static JavaVMOption *options;
static int numOptions, maxOptions;

/*
 * Prototypes for functions internal to launcher.
 */
static void AddOption(char *str, void *info);
static void SetClassPath(char *s);
static jboolean ParseArguments(int *pargc, char ***pargv, char **pjarfile,
			       char **pclassname, int *pret);
static jboolean InitializeJVM(JavaVM **pvm, JNIEnv **penv,
			      InvocationFunctions *ifn);
static jstring NewPlatformString(JNIEnv *env, char *s);
static jobjectArray NewPlatformStringArray(JNIEnv *env, char **strv, int strc);
static jclass LoadClass(JNIEnv *env, char *name);
static jstring GetMainClassName(JNIEnv *env, char *jarname);
static void SetJavaCommandLineProp(char* classname, char* jarfile, int argc, char** argv);


static void PrintJavaVersion(JNIEnv *env);


/* Support for options such as -hotspot, -classic etc. */
#define INIT_MAX_KNOWN_VMS	10
#define VM_UNKNOWN		-1
#define VM_KNOWN		 0
#define VM_ALIASED_TO		 1
#define VM_WARN			 2
#define VM_ERROR		 3
struct vmdesc {
    char *name;
    int flag;
    char *alias;
};
static struct vmdesc *knownVMs = NULL;
static int knownVMsCount = 0;
static int knownVMsLimit = 0;

static void GrowKnownVMs();
static int  KnownVMIndex(const char* name);
static void FreeKnownVMs();

/* flag which if set suppresses error messages from the launcher */
static int noExitErrorMessage = 0;

jboolean ServerClassMachine();

/*
 * Entry point.
 */
int
launchJVM(int argc, char ** argv)
{
    JavaVM *vm = 0;
    JNIEnv *env = 0;
    char *jarfile = 0;
    char *classname = 0;
    char *s = 0;
    char *main_class = NULL;
    jstring mainClassName;
    jclass mainClass;
    jmethodID mainID;
    jobjectArray mainArgs;
    int ret;
    InvocationFunctions ifn;
    char jrepath[MAXPATHLEN], jvmpath[MAXPATHLEN];
    char ** original_argv = argv;
	int create_ret;

    /* 
     * Error message to print or display; by default the message will
     * only be displayed in a window.
     */
    char * message = "Fatal exception occurred.  Program will exit.";
    jboolean messageDest = JNI_FALSE;

    /* copy original argv */
    {
      int i;
      original_argv = (char**)MemAlloc(sizeof(char*)*(argc+1));
      for(i = 0; i < argc+1; i++)
		original_argv[i] = argv[i];
    }

    create_ret = CreateExecutionEnvironment(&argc, &argv,
			       jrepath, sizeof(jrepath),
			       jvmpath, sizeof(jvmpath),
			       original_argv);
	if(create_ret != 0)
		return ERROR_COULDNT_FIND_JVM;

    ifn.CreateJavaVM = 0;
    ifn.GetDefaultJavaVMInitArgs = 0;

    if (!LoadJavaVM(jvmpath, &ifn)) {
		return ERROR_COULDNT_LOAD_JVM;
    }

	// ignore the program name when processing arguments.
    ++argv;
    --argc;

    /* 
     *  Parse command line options; if the return value of
     *  ParseArguments is false, the program should exit.
     */
    if (!ParseArguments(&argc, &argv, &jarfile, &classname, &ret)) {
      return ERROR_COULDNT_PARSE_ARGUMENTS;
    }

    /* Override class path if -jar flag was specified */
    if (jarfile != 0) {
	SetClassPath(jarfile);
    }

    /* set the -Dsun.java.command pseudo property */
    SetJavaCommandLineProp(classname, jarfile, argc, argv);

    /* Initialize the virtual machine */

    if (!InitializeJVM(&vm, &env, &ifn)) {
		ReportErrorMessage("Could not create the Java virtual machine.", JNI_TRUE);
		return ERROR_COULDNT_INITIALIZE_JVM;
    }

    if (printVersion || showVersion) {
        PrintJavaVersion(env);
	if ((*env)->ExceptionOccurred(env)) {
	    ReportExceptionDescription(env);
	    goto leave;
	}
	if (printVersion) {
	    ret = 0;
	    message = NULL;
	    goto leave;
	}
	if (showVersion) {
	    fprintf(stderr, "\n");
	}
    }

    /* If the user specified neither a class name nor a JAR file */
    if (jarfile == 0 && classname == 0) {
		message = NULL;
		goto leave;
    }

    FreeKnownVMs();  /* after last possible PrintUsage() */


    /* At this stage, argc/argv have the applications' arguments */

    ret = 1;

    /* Get the application's main class */
    if (jarfile != 0) {
	mainClassName = GetMainClassName(env, jarfile);
	if ((*env)->ExceptionOccurred(env)) {
	    ReportExceptionDescription(env);
	    goto leave;
	}
	if (mainClassName == NULL) {
	  const char * format = "Failed to load Main-Class manifest "
	                        "attribute from\n%s";
	  message = (char*)MemAlloc((strlen(format) + strlen(jarfile)) *
				    sizeof(char));
	  sprintf(message, format, jarfile);
	  messageDest = JNI_TRUE;
	  goto leave;
	}
	classname = (char *)(*env)->GetStringUTFChars(env, mainClassName, 0);
	if (classname == NULL) {
	    ReportExceptionDescription(env);
	    goto leave;
	}
	mainClass = LoadClass(env, classname);
	if(mainClass == NULL) { /* exception occured */
	    ReportExceptionDescription(env);
	    message = "Could not find the main class.  Program will exit.";
	    goto leave;
	}
	(*env)->ReleaseStringUTFChars(env, mainClassName, classname);
    } else {
      mainClassName = NewPlatformString(env, classname);
      if (mainClassName == NULL) {
	const char * format = "Failed to load Main Class: %s";
	message = (char *)MemAlloc((strlen(format) + strlen(classname)) * 
				   sizeof(char) );
	sprintf(message, format, classname); 
	messageDest = JNI_TRUE;
	goto leave;
      }
      classname = (char *)(*env)->GetStringUTFChars(env, mainClassName, 0);
      if (classname == NULL) {
	ReportExceptionDescription(env);
	goto leave;
      }
      mainClass = LoadClass(env, classname);
      if(mainClass == NULL) { /* exception occured */
	ReportExceptionDescription(env);
	message = "Could not find the main class. Program will exit.";
	goto leave;
      }
      (*env)->ReleaseStringUTFChars(env, mainClassName, classname);
    }

    /* Get the application's main method */
    mainID = (*env)->GetStaticMethodID(env, mainClass, "main",
				       "([Ljava/lang/String;)V");
    if (mainID == NULL) {
	if ((*env)->ExceptionOccurred(env)) {
	    ReportExceptionDescription(env);
	} else {
	  message = "No main method found in specified class.";
	  messageDest = JNI_TRUE;
	}
	goto leave;
    }

    {    /* Make sure the main method is public */
	jint mods;
	jmethodID mid;
	jobject obj = (*env)->ToReflectedMethod(env, mainClass, 
						mainID, JNI_TRUE);

	if( obj == NULL) { /* exception occurred */
	    ReportExceptionDescription(env);
	    goto leave;
	}

	mid = 
	  (*env)->GetMethodID(env, 
			      (*env)->GetObjectClass(env, obj),
			      "getModifiers", "()I");
	if ((*env)->ExceptionOccurred(env)) {
	    ReportExceptionDescription(env);
	    goto leave;
	}

	mods = (*env)->CallIntMethod(env, obj, mid);
	if ((mods & 1) == 0) { /* if (!Modifier.isPublic(mods)) ... */
	    message = "Main method not public.";
	    messageDest = JNI_TRUE;
	    goto leave;
	}
    }

    /* Build argument array */
    mainArgs = NewPlatformStringArray(env, argv, argc);
    if (mainArgs == NULL) {
	ReportExceptionDescription(env);
	goto leave;
    }

    /* Invoke main method. */
    (*env)->CallStaticVoidMethod(env, mainClass, mainID, mainArgs);
    if ((*env)->ExceptionOccurred(env)) {
	/* 
	 * Formerly, we used to call the "uncaughtException" method of
	 * the main thread group, but this was later shown to be
	 * unnecessary since the default definition merely printed out
	 * the same exception stack trace as ExceptionDescribe and
	 * could never actually be overridden by application programs.
	 */
	ReportExceptionDescription(env);
	goto leave;
    }

    /*
     * Detach the current thread so that it appears to have exited when
     * the application's main method exits.
     */
    if ((*vm)->DetachCurrentThread(vm) != 0) {
	message = "Could not detach main thread.";
	messageDest = JNI_TRUE;
	goto leave;
    }
    ret = 0;
    message = NULL;

leave:
    (*vm)->DestroyJavaVM(vm);
    if(message != NULL && !noExitErrorMessage)
      ReportErrorMessage(message, messageDest);

	return ERROR_STARTING_PROGRAM;
}


/*
 * Checks the command line options to find which JVM type was
 * specified.  If no command line option was given for the JVM type,
 * the default type is used.  The environment variable
 * JDK_ALTERNATE_VM and the command line option -XXaltjvm= are also
 * checked as ways of specifying which JVM type to invoke.
 */
char *
CheckJvmType(int *pargc, char ***argv, jboolean speculative) {
    int i, argi;
    int argc;
    char **newArgv;
    int newArgvIdx = 0;
    int isVMType;
    int jvmidx = -1;
    char *jvmtype = getenv("JDK_ALTERNATE_VM");

    argc = *pargc;

    /* To make things simpler we always copy the argv array */
    newArgv = MemAlloc((argc + 1) * sizeof(char *));

    /* The program name is always present */
    newArgv[newArgvIdx++] = (*argv)[0];

    for (argi = 1; argi < argc; argi++) {
	char *arg = (*argv)[argi];
        isVMType = 0;

 	if (strcmp(arg, "-classpath") == 0 || 
 	    strcmp(arg, "-cp") == 0) {
            newArgv[newArgvIdx++] = arg;
 	    argi++;
            if (argi < argc) {
                newArgv[newArgvIdx++] = (*argv)[argi];
            }
 	    continue;
 	}
 	if (arg[0] != '-') break;

 	/* Did the user pass an explicit VM type? */
	i = KnownVMIndex(arg);
	if (i >= 0) {
	    jvmtype = knownVMs[jvmidx = i].name + 1; /* skip the - */
	    isVMType = 1;
	    *pargc = *pargc - 1;
	}

	/* Did the user specify an "alternate" VM? */
	else if (strncmp(arg, "-XXaltjvm=", 10) == 0 || strncmp(arg, "-J-XXaltjvm=", 12) == 0) {
	    isVMType = 1;
	    jvmtype = arg+((arg[1]=='X')? 10 : 12);
	    jvmidx = -1;
	}

        if (!isVMType) {
            newArgv[newArgvIdx++] = arg;
        }
    }

    /* 
     * Finish copying the arguments if we aborted the above loop.
     * NOTE that if we aborted via "break" then we did NOT copy the
     * last argument above, and in addition argi will be less than
     * argc.
     */
    while (argi < argc) {
        newArgv[newArgvIdx++] = (*argv)[argi];
        argi++;
    }

    /* argv is null-terminated */
    newArgv[newArgvIdx] = 0;

    /* Copy back argv */
    *argv = newArgv;
    *pargc = newArgvIdx;

    /* use the default VM type if not specified (no alias processing) */
    if (jvmtype == NULL)
	return knownVMs[0].name+1;

    /* if using an alternate VM, no alias processing */
    if (jvmidx < 0)
      return jvmtype;

    /* Resolve aliases first */
    {    
      int loopCount = 0;
      while (knownVMs[jvmidx].flag == VM_ALIASED_TO) {
        int nextIdx = KnownVMIndex(knownVMs[jvmidx].alias);

        if (loopCount > knownVMsCount) {
	  if (!speculative) {
	    ReportErrorMessage("Error: Corrupt jvm.cfg file; cycle in alias list.",
			       JNI_TRUE);
	    return "ERROR";
	  } else {
	    return "ERROR";
	    /* break; */
	  }
        }

        if (nextIdx < 0) {
	  if (!speculative) {
            ReportErrorMessage2("Error: Unable to resolve VM alias %s",
				knownVMs[jvmidx].alias, JNI_TRUE);
            return "ERROR";
	  } else {
	    return "ERROR";
	  }
        }
        jvmidx = nextIdx;
        jvmtype = knownVMs[jvmidx].name+1;
	loopCount++;
      }
    }

    switch (knownVMs[jvmidx].flag) {
    case VM_WARN:
        if (!speculative) {
	    fprintf(stderr, "Warning: %s VM not supported; %s VM will be used\n", 
		    jvmtype, knownVMs[0].name + 1);
        }
	jvmtype = knownVMs[jvmidx=0].name + 1;
	/* fall through */
    case VM_KNOWN:
	break;
    case VM_ERROR:
        if (!speculative) {
	    ReportErrorMessage2("Error: %s VM not supported", jvmtype, JNI_TRUE);
	    return "ERROR";
        } else {
	    return "ERROR";
        }
    }

    return jvmtype;
}


/*
 * Adds a new VM option with the given given name and value.
 */
static void
AddOption(char *str, void *info)
{
    /*
     * Expand options array if needed to accommodate at least one more
     * VM option.
     */
    if (numOptions >= maxOptions) {
	if (options == 0) {
	    maxOptions = 4;
	    options = MemAlloc(maxOptions * sizeof(JavaVMOption));
	} else {
	    JavaVMOption *tmp;
	    maxOptions *= 2;
	    tmp = MemAlloc(maxOptions * sizeof(JavaVMOption));
	    memcpy(tmp, options, numOptions * sizeof(JavaVMOption));
	    free(options);
	    options = tmp;
	}
    }
    options[numOptions].optionString = str;
    options[numOptions++].extraInfo = info;
}

static void
SetClassPath(char *s)
{
    char *def = MemAlloc(strlen(s) + 40);
    sprintf(def, "-Djava.class.path=%s", s);
    AddOption(def, NULL);
}

/*
 * Parses command line arguments.  Returns JNI_FALSE if launcher
 * should exit without starting vm (e.g. certain version and usage
 * options); returns JNI_TRUE if vm needs to be started to process
 * given options.  *pret (the launcher process return value) is set to
 * 0 for a normal exit.
 */
static jboolean
ParseArguments(int *pargc, char ***pargv, char **pjarfile,
		       char **pclassname, int *pret)
{
    int argc = *pargc;
    char **argv = *pargv;
    jboolean jarflag = JNI_FALSE;
    char *arg;

    *pret = 1;
    while ((arg = *argv) != 0 && *arg == '-') {
		argv++; --argc;
		if (strcmp(arg, "-classpath") == 0 || strcmp(arg, "-cp") == 0) {
			if (argc < 1) {
				ReportErrorMessage2("%s requires class path specification",
						arg, JNI_TRUE);
			return JNI_FALSE;
			}
			SetClassPath(*argv);
			argv++; --argc;
		} else if (strcmp(arg, "-jar") == 0) {
			jarflag = JNI_TRUE;
		} else if (strcmp(arg, "-version") == 0) {
			printVersion = JNI_TRUE;
			return JNI_TRUE;
		} else if (strcmp(arg, "-showversion") == 0) {
			showVersion = JNI_TRUE;
		} else if (strcmp(arg, "-X") == 0) {
			*pret = 0;
			return JNI_FALSE;
	/*
	* The following case provide backward compatibility with old-style
	* command line options.
	*/
		} else if (strcmp(arg, "-verbosegc") == 0) {
			AddOption("-verbose:gc", NULL);
		} else if (strcmp(arg, "-t") == 0) {
			AddOption("-Xt", NULL);
		} else if (strcmp(arg, "-tm") == 0) {
			AddOption("-Xtm", NULL);
		} else if (strcmp(arg, "-debug") == 0) {
			AddOption("-Xdebug", NULL);
		} else if (strcmp(arg, "-noclassgc") == 0) {
			AddOption("-Xnoclassgc", NULL);
		} else if (strcmp(arg, "-Xfuture") == 0) {
			AddOption("-Xverify:all", NULL);
		} else if (strcmp(arg, "-verify") == 0) {
			AddOption("-Xverify:all", NULL);
		} else if (strcmp(arg, "-verifyremote") == 0) {
			AddOption("-Xverify:remote", NULL);
		} else if (strcmp(arg, "-noverify") == 0) {
			AddOption("-Xverify:none", NULL);
		} else if (strcmp(arg, "-XXsuppressExitMessage") == 0) {
			noExitErrorMessage = 1;
		} else if (strncmp(arg, "-prof", 5) == 0) {
			char *p = arg + 5;
			char *tmp = MemAlloc(strlen(arg) + 50);
			if (*p) {
				sprintf(tmp, "-Xrunhprof:cpu=old,file=%s", p + 1);
			} else {
				sprintf(tmp, "-Xrunhprof:cpu=old,file=java.prof");
			}
			AddOption(tmp, NULL);
		} else if (strncmp(arg, "-ss", 3) == 0 ||
			strncmp(arg, "-oss", 4) == 0 ||
			strncmp(arg, "-ms", 3) == 0 ||
			strncmp(arg, "-mx", 3) == 0) {
			char *tmp = MemAlloc(strlen(arg) + 6);
			sprintf(tmp, "-X%s", arg + 1); /* skip '-' */
			AddOption(tmp, NULL);
		} else if (strcmp(arg, "-checksource") == 0 ||
			strcmp(arg, "-cs") == 0 ||
			strcmp(arg, "-noasyncgc") == 0) {
			/* No longer supported */
			fprintf(stderr,
				"Warning: %s option is no longer supported.\n",
				arg);
		} else if (strncmp(arg, "-version:", 9) == 0 ||
					strcmp(arg, "-no-jre-restrict-search") == 0 ||
					strcmp(arg, "-jre-restrict-search") == 0) {
			; /* Ignore machine independent options already handled */
		} else if (RemovableMachineDependentOption(arg) ) {
			; /* Do not pass option to vm. */
		}
		else {
			AddOption(arg, NULL);
		}
    }

    if (--argc >= 0) {
        if (jarflag) {
			*pjarfile = *argv++;
			*pclassname = 0;
		} else {
		    *pjarfile = 0;
			*pclassname = *argv++;
		}
		*pargc = argc;
		*pargv = argv;
    }

    return JNI_TRUE;
}

/*
 * Initializes the Java Virtual Machine. Also frees options array when
 * finished.
 */
static jboolean
InitializeJVM(JavaVM **pvm, JNIEnv **penv, InvocationFunctions *ifn)
{
    JavaVMInitArgs args;
    jint r;

    memset(&args, 0, sizeof(args));
    args.version  = JNI_VERSION_1_2;
    args.nOptions = numOptions;
    args.options  = options;
    args.ignoreUnrecognized = JNI_FALSE;

    r = ifn->CreateJavaVM(pvm, (void **)penv, &args);
    free(options);
    return r == JNI_OK;
}


#define NULL_CHECK0(e) if ((e) == 0) return 0
#define NULL_CHECK(e) if ((e) == 0) return

/*
 * Returns a pointer to a block of at least 'size' bytes of memory.
 * Prints error message and exits if the memory could not be allocated.
 */
void *
MemAlloc(size_t size)
{
    void *p = malloc(size);
    if (p == 0) {
	perror("malloc");
	exit(1);
    }
    return p;
}

/*
 * Returns a new Java string object for the specified platform string.
 */
static jstring
NewPlatformString(JNIEnv *env, char *s)
{    
    int len = (int)strlen(s);
    jclass cls;
    jmethodID mid;
    jbyteArray ary;

    if (s == NULL)
	return 0;
    NULL_CHECK0(cls = (*env)->FindClass(env, "java/lang/String"));
    NULL_CHECK0(mid = (*env)->GetMethodID(env, cls, "<init>", "([B)V"));
    ary = (*env)->NewByteArray(env, len);
    if (ary != 0) {
	jstring str = 0;
	(*env)->SetByteArrayRegion(env, ary, 0, len, (jbyte *)s);
	if (!(*env)->ExceptionOccurred(env)) {
	    str = (*env)->NewObject(env, cls, mid, ary);
	}
	(*env)->DeleteLocalRef(env, ary);
	return str;
    }
    return 0;
}

/*
 * Returns a new array of Java string objects for the specified
 * array of platform strings.
 */
static jobjectArray
NewPlatformStringArray(JNIEnv *env, char **strv, int strc)
{
    jarray cls;
    jarray ary;
    int i;

    NULL_CHECK0(cls = (*env)->FindClass(env, "java/lang/String"));
    NULL_CHECK0(ary = (*env)->NewObjectArray(env, strc, cls, 0));
    for (i = 0; i < strc; i++) {
	jstring str = NewPlatformString(env, *strv++);
	NULL_CHECK0(str);
	(*env)->SetObjectArrayElement(env, ary, i, str);
	(*env)->DeleteLocalRef(env, str);
    }
    return ary;
}

/*
 * Loads a class, convert the '.' to '/'.
 */
static jclass
LoadClass(JNIEnv *env, char *name)
{
    char *buf = MemAlloc(strlen(name) + 1);
    char *s = buf, *t = name, c;
    jclass cls;

    do {
        c = *t++;
	*s++ = (c == '.') ? '/' : c;
    } while (c != '\0');
    cls = (*env)->FindClass(env, buf);
    free(buf);

    return cls;
}

/*
 * Returns the main class name for the specified jar file.
 */
static jstring
GetMainClassName(JNIEnv *env, char *jarname)
{
#define MAIN_CLASS "Main-Class"
    jclass cls;
    jmethodID mid;
    jobject jar, man, attr;
    jstring str, result = 0;

    NULL_CHECK0(cls = (*env)->FindClass(env, "java/util/jar/JarFile"));
    NULL_CHECK0(mid = (*env)->GetMethodID(env, cls, "<init>",
					  "(Ljava/lang/String;)V"));
    NULL_CHECK0(str = NewPlatformString(env, jarname));
    NULL_CHECK0(jar = (*env)->NewObject(env, cls, mid, str));
    NULL_CHECK0(mid = (*env)->GetMethodID(env, cls, "getManifest",
					  "()Ljava/util/jar/Manifest;"));
    man = (*env)->CallObjectMethod(env, jar, mid);
    if (man != 0) {
	NULL_CHECK0(mid = (*env)->GetMethodID(env,
				    (*env)->GetObjectClass(env, man),
				    "getMainAttributes",
				    "()Ljava/util/jar/Attributes;"));
	attr = (*env)->CallObjectMethod(env, man, mid);
	if (attr != 0) {
	    NULL_CHECK0(mid = (*env)->GetMethodID(env,
				    (*env)->GetObjectClass(env, attr),
				    "getValue",
				    "(Ljava/lang/String;)Ljava/lang/String;"));
	    NULL_CHECK0(str = NewPlatformString(env, MAIN_CLASS));
	    result = (*env)->CallObjectMethod(env, attr, mid, str);
	}
    }
    return result;
}

/*
 * inject the -Dsun.java.command pseudo property into the args structure
 * this pseudo property is used in the HotSpot VM to expose the
 * Java class name and arguments to the main method to the VM. The
 * HotSpot VM uses this pseudo property to store the Java class name
 * (or jar file name) and the arguments to the class's main method
 * to the instrumentation memory region. The sun.java.command pseudo
 * property is not exported by HotSpot to the Java layer.
 */
void
SetJavaCommandLineProp(char *classname, char *jarfile,
		       int argc, char **argv)
{

    int i = 0;
    size_t len = 0;
    char* javaCommand = NULL;
    char* dashDstr = "-Dsun.java.command=";

    if (classname == NULL && jarfile == NULL) {
        /* unexpected, one of these should be set. just return without
         * setting the property
         */
        return;
    }

    /* if the class name is not set, then use the jarfile name */
    if (classname == NULL) {
        classname = jarfile;
    }

    /* determine the amount of memory to allocate assuming
     * the individual components will be space separated
     */
    len = strlen(classname);
    for (i = 0; i < argc; i++) {
        len += strlen(argv[i]) + 1;
    }

    /* allocate the memory */
    javaCommand = (char*) MemAlloc(len + strlen(dashDstr) + 1);

    /* build the -D string */
    *javaCommand = '\0';
    strcat(javaCommand, dashDstr);
    strcat(javaCommand, classname);

    for (i = 0; i < argc; i++) {
        /* the components of the string are space separated. In
         * the case of embedded white space, the relationship of
         * the white space separated components to their true
         * positional arguments will be ambiguous. This issue may
         * be addressed in a future release.
         */
        strcat(javaCommand, " ");
        strcat(javaCommand, argv[i]);
    }

    AddOption(javaCommand, NULL);
}

/*
 * Prints the version information from the java.version and other properties.
 */
static void
PrintJavaVersion(JNIEnv *env)
{
    jclass ver;
    jmethodID print;

    NULL_CHECK(ver = (*env)->FindClass(env, "sun/misc/Version"));
    NULL_CHECK(print = (*env)->GetStaticMethodID(env, ver, "print", "()V"));

    (*env)->CallStaticVoidMethod(env, ver, print);
}


/*
 * Read the jvm.cfg file and fill the knownJVMs[] array.
 *
 * The functionality of the jvm.cfg file is subject to change without 
 * notice and the mechanism will be removed in the future.
 *
 * The lexical structure of the jvm.cfg file is as follows:
 *
 *     jvmcfg         :=  { vmLine }
 *     vmLine         :=  knownLine 
 *                    |   aliasLine 
 *                    |   warnLine 
 *                    |   errorLine 
 *                    |   commentLine
 *     knownLine      :=  flag  "KNOWN"                  EOL
 *     warnLine       :=  flag  "WARN"                   EOL
 *     errorLine      :=  flag  "ERROR"                  EOL
 *     aliasLine      :=  flag  "ALIASED_TO"       flag  EOL
 *     commentLine    :=  "#" text                       EOL
 *     flag           :=  "-" identifier
 *
 * The semantics are that when someone specifies a flag on the command line:
 * - if the flag appears on a knownLine, then the identifier is used as 
 *   the name of the directory holding the JVM library (the name of the JVM).
 * - if the flag appears as the first flag on an aliasLine, the identifier 
 *   of the second flag is used as the name of the JVM.
 * - if the flag appears on a warnLine, the identifier is used as the 
 *   name of the JVM, but a warning is generated.
 * - if the flag appears on an errorLine, an error is generated.
 * If no flag is given on the command line, the first vmLine of the jvm.cfg 
 * file determines the name of the JVM.
 *
 * The intent of the jvm.cfg file is to allow several JVM libraries to 
 * be installed in different subdirectories of a single JRE installation, 
 * for space-savings and convenience in testing.  
 * The intent is explicitly not to provide a full aliasing or predicate
 * mechanism.
 */
jint
ReadKnownVMs(const char *jrepath, char * arch, jboolean speculative)
{
    FILE *jvmCfg;
    char jvmCfgName[MAXPATHLEN+20];
    char line[MAXPATHLEN+20];
    int cnt = 0;
    int lineno = 0;
    int vmType;
    char *tmpPtr;
    char *altVMName;
	struct stat s;
    static char *whiteSpace = " \t";

	sprintf(jvmCfgName, "%s\\lib\\%s\\jvm.cfg\0", jrepath, arch);
	if(stat(jvmCfgName, &s) != 0)
		// try without arch -- for java 1.3 compatability
		sprintf(jvmCfgName, "%s\\lib\\jvm.cfg\0", jrepath);
    
    jvmCfg = fopen(jvmCfgName, "r");
    if (jvmCfg == NULL) {
		if (!speculative) {
			ReportErrorMessage2("Error: could not open `%s'", jvmCfgName,
					JNI_TRUE);
			return -1;
		} else {
			return -1;
		}
    }
    while (fgets(line, sizeof(line), jvmCfg) != NULL) {
        vmType = VM_UNKNOWN;
        lineno++;
        if (line[0] == '#')
            continue;
        if (line[0] != '-') {
            fprintf(stderr, "Warning: no leading - on line %d of `%s'\n",
                    lineno, jvmCfgName);
        }
        if (cnt >= knownVMsLimit) {
            GrowKnownVMs(cnt);
        }
        line[strlen(line)-1] = '\0'; /* remove trailing newline */
        tmpPtr = line + strcspn(line, whiteSpace);
        if (*tmpPtr == 0) {
            fprintf(stderr, "Warning: missing VM type on line %d of `%s'\n",
                    lineno, jvmCfgName);
			vmType = VM_KNOWN; // assume known, for java 1.3 compatability
        } else {
            /* Null-terminate this string for strdup below */
            *tmpPtr++ = 0;
            tmpPtr += strspn(tmpPtr, whiteSpace);
            if (*tmpPtr == 0) {
                fprintf(stderr, "Warning: missing VM type on line %d of `%s'\n",
                        lineno, jvmCfgName);
            } else {
                if (!strncmp(tmpPtr, "KNOWN", strlen("KNOWN"))) {
                    vmType = VM_KNOWN;
                } else if (!strncmp(tmpPtr, "ALIASED_TO", strlen("ALIASED_TO"))) {
                    tmpPtr += strcspn(tmpPtr, whiteSpace);
                    if (*tmpPtr != 0) {
                        tmpPtr += strspn(tmpPtr, whiteSpace);
                    }
                    if (*tmpPtr == 0) {
                        fprintf(stderr, "Warning: missing VM alias on line %d of `%s'\n",
                                lineno, jvmCfgName);
                    } else {
                        /* Null terminate altVMName */
                        altVMName = tmpPtr;
                        tmpPtr += strcspn(tmpPtr, whiteSpace);
                        *tmpPtr = 0;
                        vmType = VM_ALIASED_TO;
                    }
                } else if (!strncmp(tmpPtr, "WARN", strlen("WARN"))) {
                    vmType = VM_WARN;
                } else if (!strncmp(tmpPtr, "ERROR", strlen("ERROR"))) {
                    vmType = VM_ERROR;
                } else {
                    fprintf(stderr, "Warning: unknown VM type on line %d of `%s'\n",
                            lineno, &jvmCfgName[0]);
                    vmType = VM_KNOWN;
                }
            }
        }

        if (vmType != VM_UNKNOWN) {
            knownVMs[cnt].name = strdup(line);
            knownVMs[cnt].flag = vmType;
            switch (vmType) {
            default:
                break;
            case VM_ALIASED_TO:
                knownVMs[cnt].alias = strdup(altVMName);
                break;
            }
            cnt++;
        }
    }
    fclose(jvmCfg);
    knownVMsCount = cnt;


    return cnt;
}


static void
GrowKnownVMs(int minimum)
{
    struct vmdesc* newKnownVMs;
    int newMax;

    newMax = (knownVMsLimit == 0 ? INIT_MAX_KNOWN_VMS : (2 * knownVMsLimit));
    if (newMax <= minimum) {
        newMax = minimum;
    }
    newKnownVMs = (struct vmdesc*) MemAlloc(newMax * sizeof(struct vmdesc));
    if (knownVMs != NULL) {
        memcpy(newKnownVMs, knownVMs, knownVMsLimit * sizeof(struct vmdesc));
    }
    free(knownVMs);
    knownVMs = newKnownVMs;
    knownVMsLimit = newMax;
}


/* Returns index of VM or -1 if not found */
static int
KnownVMIndex(const char* name)
{
    int i;
    if (strncmp(name, "-J", 2) == 0) name += 2;
    for (i = 0; i < knownVMsCount; i++) {
        if (!strcmp(name, knownVMs[i].name)) {
            return i;
        }
    }
    return -1;
}

static void
FreeKnownVMs()
{
    int i;
    for (i = 0; i < knownVMsCount; i++) {
        free(knownVMs[i].name);
        knownVMs[i].name = NULL;
    }
    free(knownVMs);
}
