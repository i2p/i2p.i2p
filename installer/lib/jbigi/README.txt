NOTE: This file may not be maintained.
See history.txt, checkin comments, javadoc, and code in
NativeBigInteger.java and CPUID.java for additional information.
See NativeBigInteger.java for naming rules and algorithms for
generating an ordered list of names to load.

================================

jbigi.jar was built by jrandom on Aug 21, 2004 with the jbigi and jcpuid 
native libraries compiled on linux, winXP (w/ MinGW), and freebsd (4.8).  
The GMP code in jbigi is from GMP-4.1.3 (http://www.swox.com/gmp/), and 
was optimized for a variety of CPU architectures.

On Sep 16, 2005, libjbigi-osx-none.jnilib was added to jbigi.jar after
being compiled by jrandom on osx/ppc with GMP-4.1.4.

On Sep 18, 2005, libjbigi-linux-athlon64.so was added to jbigi.jar after
being compiled by jrandom on linux/p4 (cross compiled to --host=x86_64)
with GMP-4.1.4.

On Nov 29, 2005, the libjbigi-osx-none.jnilib was added back to
jbigi.jar after being mistakenly removed in the Sep 18 update (d'oh!)

On Dec 30, 2005, the libjcpuid-x86-linux.so was updated to use the 
(year old) C version of jcpuid, rather than the C++ version.  This removes
the libg++.so.5 dependency that has been a problem for a few linux distros.

On Feb 8, 2006, the libjbigi-linux-viac3.so was added to jbigi.jar after
being compiled by jrandom on linux/p4 (cross compiled to --host=viac3)

On Feb 27, 2006, jbigi-win-athlon.dll was copied to jbigi-win-athlon64.dll,
as it should offer amd64 users better performance than jbigi-win-none.dll
until we get a full amd64 build.

================================

Updates May/June 2011:
  jcpuid:
   - jcpuid.c updated to be compatible with -fPIC
   - 32 bit libjcpuid-linux-x86.so updated, compiled with -fPIC.
   - 64 bit libjcpuid-linux-x86_64.so added, compiled with -fPIC.
   - See also javadoc and code in CPUID.java
  jbigi:
   - k62 and k63 are identical for all except windows; exception added to
     NativeBigInteger to use k62 for k63. k63 files deleted.
   - All 32 bit linux files updated with GMP 4.3.2, compiled with -fPIC,
     except for athlon64 and pentium4, which use GMP 5.0.2.
   - All 64 bit linux files updated with GMP 5.0.2.
   - libjbigi-windows-athlon64.dll deleted, it was a duplicate of
     libjbigi-windows-athlon.dll. NativeBigInteger now uses athlon as
     a fallback for all 64-bit processors.
   - Note that all new 64 bit files will use the _64 suffix. For example,
     the old libjbigi-linux-athlon64.so file was 64 bit; now it is 32 bit
     and the 64 bit file is libjbigi-linux-athlon64_64.so.
   - The 4.3.2 files are half the size of the 5.0.2 files, and there was
     little or no performance difference for 32 bit, so we are using
     4.3.2 for 32 bit. For 64-bit processors, both performance testing and
     the GMP changelog led us to use 5.0.2 for both the 32- and 64-bit versions.
   - See also checkin comments, javadoc and code in NativeBigInteger.java
