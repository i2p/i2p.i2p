package freenet.support.CPUInformation;

/**
 *  Moved out of CPUID.java
 *  @since 0.8.7
 */
class IntelInfoImpl extends CPUIDCPUInfo implements IntelCPUInfo
{
    protected static boolean isPentiumCompatible = false;
    protected static boolean isPentiumMMXCompatible = false;
    protected static boolean isPentium2Compatible = false;
    protected static boolean isPentium3Compatible = false;
    protected static boolean isPentium4Compatible = false;
    protected static boolean isPentiumMCompatible = false;
    protected static boolean isAtomCompatible = false;
    protected static boolean isCore2Compatible = false;
    protected static boolean isCoreiCompatible = false;
    
    // If modelString != null, the cpu is considered correctly identified.
    protected static String modelString = null;
    
    public boolean IsPentiumCompatible(){ return isPentiumCompatible; }

    public boolean IsPentiumMMXCompatible(){ return isPentiumMMXCompatible; }

    public boolean IsPentium2Compatible(){ return isPentium2Compatible; }

    public boolean IsPentium3Compatible(){ return isPentium3Compatible; }

    public boolean IsPentium4Compatible(){ return isPentium4Compatible; }

    public boolean IsPentiumMCompatible(){ return isPentiumMCompatible; }

    public boolean IsAtomCompatible(){ return isAtomCompatible; }

    public boolean IsCore2Compatible(){ return isCore2Compatible; }

    public boolean IsCoreiCompatible(){ return isCoreiCompatible; }    
	
	static
	{
		identifyCPU();
	}

    public String getCPUModelString() throws UnknownCPUException
    {
        if (modelString != null)
            return modelString;
        throw new UnknownCPUException("Unknown Intel CPU; Family="+CPUID.getCPUFamily()+", Model="+CPUID.getCPUModel());
    }
    
    private synchronized static void identifyCPU()
    {
        if (CPUID.getCPUExtendedModel() == 0){
            if(CPUID.getCPUFamily() == 4){
                switch(CPUID.getCPUModel()){
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
                }
            }
        }
        if (CPUID.getCPUExtendedModel() == 0){
            if(CPUID.getCPUFamily() == 5){
                isPentiumCompatible = true;
                switch(CPUID.getCPUModel()){
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
                }
            }
        }
        if(CPUID.getCPUFamily() == 6){
            if (CPUID.getCPUExtendedModel() == 0){
                isPentiumCompatible = true;
                isPentiumMMXCompatible = true;
                switch(CPUID.getCPUModel()){
                    case 0:
                        modelString = "Pentium Pro A-step";
                        break;
                    case 1:
                        modelString = "Pentium Pro";
                        break;
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
						isPentiumMCompatible = true;
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
                        isPentium2Compatible = true;
                        isPentium3Compatible = true;
						isPentiumMCompatible = true;
                        isCore2Compatible = true;
                        isX64 = true;
                        modelString = "Core 2 (Conroe)";
                        break;
                    case 15:
                        isPentium2Compatible = true;
                        isPentium3Compatible = true;
						isPentiumMCompatible = true;
                        isCore2Compatible = true;
                        isX64 = true;
                        modelString = "Core 2 (Conroe)";
                        break;
                }
            } else if (CPUID.getCPUExtendedModel() == 1){
                isPentiumCompatible = true;
                isPentiumMMXCompatible = true;
                isPentium2Compatible = true;
                isPentium3Compatible = true;
                isPentium4Compatible = true;
				isPentiumMCompatible = true;
                isCore2Compatible = true;
                isX64 = true;
                switch(CPUID.getCPUModel()){
                    case 6:
                        modelString = "Celeron";
                        break;
                     case 10:
                        isCoreiCompatible = true;
                         modelString = "Core i7 (45nm)";
                        break;
                     case 12:
                        isAtomCompatible = true;
                        isCore2Compatible = false;
                        isPentium4Compatible = false;
                        isX64 = true;
                        modelString = "Atom";
                        break;
                     case 13:
                        isCoreiCompatible = true;
                        modelString = "Xeon MP (45nm)";
                        break;
                    case 14:
                        isCoreiCompatible = true;
                        modelString = "Core i5/i7 (45nm)";
                        break;
                }
            } else if (CPUID.getCPUExtendedModel() == 2){
                isPentiumCompatible = true;
                isPentiumMMXCompatible = true;
                isPentium2Compatible = true;
                isPentium3Compatible = true;
                isPentium4Compatible = true;
				isPentiumMCompatible = true;
                isCore2Compatible = true;
                isCoreiCompatible = true;
                isX64 = true;
                switch(CPUID.getCPUModel()){
                    case 5:
                        modelString = "Core i3 or i5/i7 mobile (32nm)";
                        break;
                    case 10:
                        modelString = "Core i7/i5 (32nm)";
                        break;
                    case 12:
                        modelString = "Core i7 (32nm)";
                        break;
                    case 14:
                        modelString = "Xeon MP (45nm)";
                        break;
                    case 15:
                        modelString = "Xeon MP (32nm)";
                        break;
                }                
            }
        }
        if(CPUID.getCPUFamily() == 7){
            switch(CPUID.getCPUModel()){
                //Itanium..  TODO
            }
        }
        if(CPUID.getCPUFamily() == 15){
            if(CPUID.getCPUExtendedFamily() == 0){
                isPentiumCompatible = true;
                isPentiumMMXCompatible = true;
                isPentium2Compatible = true;
                isPentium3Compatible = true;
                isPentium4Compatible = true;
                switch(CPUID.getCPUModel()){
                    case 0:
                        modelString = "Pentium IV (180 nm)";
                        break;
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
                }
            }
            if(CPUID.getCPUExtendedFamily() == 1){
                switch(CPUID.getCPUModel()){
                    //    Itanium 2.. TODO
                }    
            }
        }
    }

	public boolean hasX64() {
		return isX64;
	}
}
