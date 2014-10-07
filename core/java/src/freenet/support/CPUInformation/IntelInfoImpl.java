package freenet.support.CPUInformation;

/**
 *  Moved out of CPUID.java
 *
 *  Ref: https://software.intel.com/en-us/articles/intel-architecture-and-processor-identification-with-cpuid-model-and-family-numbers
 *  Ref: http://en.wikipedia.org/wiki/List_of_Intel_CPU_microarchitectures
 *
 *  @since 0.8.7
 */
class IntelInfoImpl extends CPUIDCPUInfo implements IntelCPUInfo
{
    private static boolean isPentiumCompatible;
    private static boolean isPentiumMMXCompatible;
    private static boolean isPentium2Compatible;
    private static boolean isPentium3Compatible;
    private static boolean isPentium4Compatible;
    private static boolean isPentiumMCompatible;
    private static boolean isAtomCompatible;
    private static boolean isCore2Compatible;
    private static boolean isCoreiCompatible;
    
    // If modelString != null, the cpu is considered correctly identified.
    private static final String smodel = identifyCPU();
    
    public boolean IsPentiumCompatible(){ return isPentiumCompatible; }

    public boolean IsPentiumMMXCompatible(){ return isPentiumMMXCompatible; }

    public boolean IsPentium2Compatible(){ return isPentium2Compatible; }

    public boolean IsPentium3Compatible(){ return isPentium3Compatible; }

    public boolean IsPentium4Compatible(){ return isPentium4Compatible; }

    public boolean IsPentiumMCompatible(){ return isPentiumMCompatible; }

    public boolean IsAtomCompatible(){ return isAtomCompatible; }

    /**
     * Supports the SSE 3 instructions
     */
    public boolean IsCore2Compatible(){ return isCore2Compatible; }

    /**
     * Supports the SSE 3, 4.1, 4.2 instructions.
     * In general, this requires 45nm or smaller process.
     */
    public boolean IsCoreiCompatible(){ return isCoreiCompatible; }    

    public String getCPUModelString() throws UnknownCPUException
    {
        if (smodel != null)
            return smodel;
        throw new UnknownCPUException("Unknown Intel CPU; Family="+CPUID.getCPUFamily() + '/' + CPUID.getCPUExtendedFamily()+
                                      ", Model="+CPUID.getCPUModel() + '/' + CPUID.getCPUExtendedModel());
    }
    
    private static String identifyCPU()
    {
        // http://en.wikipedia.org/wiki/Cpuid
        // http://web.archive.org/web/20110307080258/http://www.intel.com/Assets/PDF/appnote/241618.pdf
        // http://www.intel.com/content/dam/www/public/us/en/documents/manuals/64-ia-32-architectures-software-developer-vol-2a-manual.pdf
        String modelString = null;
        int family = CPUID.getCPUFamily();
        int model = CPUID.getCPUModel();
        if (family == 15 || family == 6) {
            // Intel uses extended model for family = 15 or family = 6,
            // which is not what wikipedia says
            model += CPUID.getCPUExtendedModel() << 4;
        }
        if (family == 15) {
            family += CPUID.getCPUExtendedFamily();
        }

        switch (family) {
            case 4: {
                switch (model) {
                    case 0:
                        modelString = "486 DX-25/33";
                        break;
                    case 1:
                        modelString = "486 DX-50";
                        break;
                    case 2:
                        modelString = "486 SX";
                        break;
                    case 3:
                        modelString = "486 DX/2";
                        break;
                    case 4:
                        modelString = "486 SL";
                        break;
                    case 5:
                        modelString = "486 SX/2";
                        break;
                    case 7:
                        modelString = "486 DX/2-WB";
                        break;
                    case 8:
                        modelString = "486 DX/4";
                        break;
                    case 9:
                        modelString = "486 DX/4-WB";
                        break;
                    default:
                        modelString = "Intel 486/586 model " + model;
                        break;
                }
            }
            break;

            // P5
            case 5: {
                isPentiumCompatible = true;
                switch (model) {
                    case 0:
                        modelString = "Pentium 60/66 A-step";
                        break;
                    case 1:
                        modelString = "Pentium 60/66";
                        break;
                    case 2:
                        modelString = "Pentium 75 - 200";
                        break;
                    case 3:
                        modelString = "OverDrive PODP5V83";
                        break;
                    case 4:
                        isPentiumMMXCompatible = true;
                        modelString = "Pentium MMX";
                        break;
                    case 7:
                        modelString = "Mobile Pentium 75 - 200";
                        break;
                    case 8:
                        isPentiumMMXCompatible = true;
                        modelString = "Mobile Pentium MMX";
                        break;
                    default:
                        modelString = "Intel Pentium model " + model;
                        break;
                }
            }
            break;

            // P6
            case 6: {
                isPentiumCompatible = true;
                isPentiumMMXCompatible = true;
                int extmodel = model >> 4;
                if (extmodel >= 1) {
                    isPentium2Compatible = true;
                    isPentium3Compatible = true;
                    isPentium4Compatible = true;
                    isPentiumMCompatible = true;
                    isCore2Compatible = true;
                    isX64 = true;
                    if (extmodel >= 2)
                        isCoreiCompatible = true;
                }
                switch (model) {
                    case 0:
                        modelString = "Pentium Pro A-step";
                        break;
                    case 1:
                        modelString = "Pentium Pro";
                        break;
                    // Spoofed Nehalem by qemu-kvm
                    // Not in any CPUID charts
                    // KVM bug?
                    // # cat /usr/share/kvm/cpus-x86_64.conf | grep 'name = "Nehalem"' -B 1 -A 12
                    // [cpudef]
                    //    name = "Nehalem"
                    //    level = "2"
                    //    vendor = "GenuineIntel"
                    //    family = "6"
                    //    model = "2"
                    //    stepping = "3"
                    //    feature_edx = "sse2 sse fxsr mmx clflush pse36 pat cmov mca pge mtrr sep apic cx8 mce pae msr tsc pse de fpu"
                    //    feature_ecx = "popcnt sse4.2 sse4.1 cx16 ssse3 sse3"
                    //    extfeature_edx = "i64 syscall xd"
                    //    extfeature_ecx = "lahf_lm"
                    //    xlevel = "0x8000000A"
                    //    model_id = "Intel Core i7 9xx (Nehalem Class Core i7)"
                    //case 2:
                        // ...
                    case 3:
                        isPentium2Compatible = true;
                        modelString = "Pentium II (Klamath)";
                        break;
                    case 5:
                        isPentium2Compatible = true;
                        modelString = "Pentium II (Deschutes), Celeron (Covington), Mobile Pentium II (Dixon)";
                        break;
                    case 6:
                        isPentium2Compatible = true;
                        modelString = "Mobile Pentium II, Celeron (Mendocino)";
                        break;
                    case 7:
                        isPentium2Compatible = true;
                        isPentium3Compatible = true;
                        modelString = "Pentium III (Katmai)";
                        break;
                    case 8:
                        isPentium2Compatible = true;
                        isPentium3Compatible = true;
                        modelString = "Pentium III (Coppermine), Celeron w/SSE";
                        break;
                    case 9:
                        isPentium2Compatible = true;
                        isPentium3Compatible = true;
                        isPentiumMCompatible = true;
                        isX64 = true;
                        modelString = "Pentium M (Banias)";
                        break;
                    case 10:
                        isPentium2Compatible = true;
                        isPentium3Compatible = true;
                        modelString = "Pentium III Xeon (Cascades)";
                        break;
                    case 11:
                        isPentium2Compatible = true;
                        isPentium3Compatible = true;
                        modelString = "Pentium III (130 nm)";
                        break;
                    case 13:
                        isPentium2Compatible = true;
                        isPentium3Compatible = true;
                        isPentiumMCompatible = true;
                        isX64 = true;
                        modelString = "Core (Yonah)";
                        break;
                    case 14:
                    case 15:
                        isPentium2Compatible = true;
                        isPentium3Compatible = true;
                        isPentiumMCompatible = true;
                        isCore2Compatible = true;
                        isX64 = true;
                        modelString = "Core 2 (Conroe)";
                        break;

                // following are for extended model == 1
                // most flags are set above

                    // Celeron 65 nm
                    case 0x16:
                        modelString = "Celeron";
                        break;
                    // Penryn 45 nm
                    case 0x17:
                        modelString = "Core 2 (45nm)";
                        break;
                    // Nehalem 45 nm
                    case 0x1a:
                        isCoreiCompatible = true;
                        modelString = "Core i7 (45nm)";
                        break;
                    // Atom Pineview / Silverthorne 45 nm
                    case 0x1c:
                        isAtomCompatible = true;
                        // Some support SSE3? true for Pineview? TBD...
                        isCore2Compatible = false;
                        isPentium4Compatible = false;
                        isX64 = true;
                        modelString = "Atom";
                        break;
                    // Penryn 45 nm
                    case 0x1d:
                        isCoreiCompatible = true;
                        modelString = "Xeon MP (45nm)";
                        break;
                    // Nehalem 45 nm
                    case 0x1e:
                        isCoreiCompatible = true;
                        modelString = "Core i5/i7 (45nm)";
                        break;

                // following are for extended model == 2
                // most flags are set above
                // isCoreiCompatible = true is the default

                    // Westmere 32 nm
                    case 0x25:
                        modelString = "Core i3 or i5/i7 mobile (32nm)";
                        break;
                    // Atom Lincroft 45 nm
                    case 0x26:
                        isAtomCompatible = true;
                        // Supports SSE 3
                        isCoreiCompatible = false;
                        modelString = "Atom Z600";
                        break;
                    // Sandy bridge 32 nm
                    case 0x2a:
                        modelString = "Sandy Bridge H/M";
                        break;
                    case 0x2b:
                        modelString = "Core i7/i5 (32nm)";
                        break;
                    // Westmere
                    case 0x2c:
                        modelString = "Core i7 (32nm)";
                        break;
                    // Sandy bridge 32 nm
                    case 0x2d:
                        modelString = "Sandy Bridge EP";
                        break;
                    // Nehalem 45 nm
                    case 0x2e:
                        modelString = "Xeon MP (45nm)";
                        break;
                    // Westmere 32 nm
                    case 0x2f:
                        modelString = "Xeon MP (32nm)";
                        break;

                // following are for extended model == 3
                // most flags are set above
                // isCoreiCompatible = true is the default

                    // Atom Cedarview 32 nm
                    case 0x36:
                        isAtomCompatible = true;
                        // Supports SSE 3
                        isCore2Compatible = false;
                        modelString = "Atom N2000/D2000";
                        break;
                    // Ivy Bridge 22 nm
                    case 0x3a:
                        modelString = "Ivy Bridge";
                        break;
                    // Haswell 22 nm
                    case 0x3c:
                        modelString = "Haswell";
                        break;
                    // Broadwell 14 nm
                    case 0x3d:
                        modelString = "Broadwell";
                        break;

                // following are for extended model == 4
                // most flags are set above
                // isCoreiCompatible = true is the default

                    // Atom Silvermont / Bay Trail / Avoton 22 nm
                    // Supports SSE 4.2
                    case 0x4d:
                        isAtomCompatible = true;
                        modelString = "Bay Trail / Avoton";
                        break;

                // others

                    default:
                        modelString = "Intel model " + model;
                        break;
                } // switch model
            } // case 6
            break;

            case 7: {
                // Flags TODO
                modelString = "Intel Itanium model " + model;
            }
            break;

            // 15 + 0
            case 15: {
                isPentiumCompatible = true;
                isPentiumMMXCompatible = true;
                isPentium2Compatible = true;
                isPentium3Compatible = true;
                isPentium4Compatible = true;
                switch (model) {
                    case 0:
                    case 1:
                        modelString = "Pentium IV (180 nm)";
                        break;
                    case 2:
                        modelString = "Pentium IV (130 nm)";
                        break;
                    case 3:
                        modelString = "Pentium IV (90 nm)";
                        break;
                    case 4:
                        isX64 = true;
                        modelString = "Pentium IV (90 nm)";
                        break;
                    case 6:
                        isX64 = true;
                        modelString = "Pentium IV (65 nm)";
                        break;
                    default:
                        modelString = "Intel Pentium IV model " + model;
                        break;
                }
            }
            break;

            // 15 + 1
            case 16: {
                // Flags TODO
                modelString = "Intel Itanium II model " + model;
            }
        }
        return modelString;
    }

    public boolean hasX64() {
        return isX64;
    }
}
