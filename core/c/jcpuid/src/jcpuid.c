#include "jcpuid.h"

/**

From: http://sam.zoy.org/blog/2007-04-13-shlib-with-non-pic-code-have-inline-assembly-and-pic-mix-well

Perhaps the most accessible documentation on what PIC code is and how an ELF dynamic linker works is
John Levine's Linkers and Loaders (and it has amazing sketches, too!). The Gentoo documentation also 
has an Introduction to Position Independent Code. I'd like to give a few hints on how to fix the
shlib-with-non-pic-code lintian error caused by inline assembly on the i386 and amd64 platforms,
as well as build errors that may occur due to inline assembly being used.

I'm not going to cover the trivial "all objects were not built using gcc's -fPIC flag" problem.
It usually requires a fix to the build system, not to the code.

  gcc can't find a register (i386)

  PIC on i386 uses a register to store the GOT (global offset table) address.
  This register is usually %ebx, making it unavailable for use by inline assembly
  (and also restricting the compiler's register usage when compiling C or C++ code).
  So the following perfectly valid code will not build with the -fPIC flag:

     void cpuid(uint32_t op, uint32_t reg[4])
     {
        asm volatile("cpuid"
                     : "=a"(reg[0]), "=b"(reg[1]), "=c"(reg[2]), "=d"(reg[3])
                     : "a"(op)
                     : "cc");
     }

  Using -fPIC, gcc will say something around the lines of error: can't find a register in class 'BREG'
  while reloading 'asm'. Several things need to be done to fix this:

    * use a register other than %ebx
    * save %ebx if it risks being clobbered by the assembly code, and don't tell gcc about %ebx at all (it doesn't need to know anyway)
    * if we saved %ebx by pushing it on the stack, make sure the inline assembly code takes the new stack offset into account

  And here is the PIC-compliant version:

**/

//Executes the indicated subfunction of the CPUID operation
JNIEXPORT jobject JNICALL Java_freenet_support_CPUInformation_CPUID_doCPUID
  (JNIEnv * env, jclass cls, jint iFunction)
{
	int a,b,c,d;
	jclass clsResult = (*env)->FindClass(env, "freenet/support/CPUInformation/CPUID$CPUIDResult");
	jmethodID constructor = (*env)->GetMethodID(env, clsResult,"<init>","(IIII)V" );
	#ifdef _MSC_VER
		//Use MSVC assembler notation
		_asm 
		{
			mov eax, iFunction
			cpuid
			mov a, eax
			mov b, ebx
			mov c, ecx
			mov d, edx
		}
	#else
		//Use GCC assembler notation
		asm volatile
		(
			"pushl %%ebx      \n\t" /* save %ebx */
			"cpuid            \n\t"
			"movl %%ebx, %1   \n\t" /* save what cpuid just put in %ebx */
			"popl %%ebx       \n\t" /* restore the old %ebx */
			: "=a" (a), "=r" (b), "=c" (c), "=d" (d)
			:"a"(iFunction)
			: "cc"
		);
	#endif
	return (*env)->NewObject(env, clsResult,constructor,a,b,c,d);
}

