package freenet.support.CPUInformation;

/**
 *  Moved out of CPUID.java
 *  @since 0.8.7
 */
class AMDInfoImpl extends CPUIDCPUInfo implements AMDCPUInfo
{
    protected static boolean isK6Compatible = false;
    protected static boolean isK6_2_Compatible = false;
    protected static boolean isK6_3_Compatible = false;
    protected static boolean isGeodeCompatible = false;
    protected static boolean isAthlonCompatible = false;
    protected static boolean isAthlon64Compatible = false;
    protected static boolean isBobcatCompatible = false;
    protected static boolean isBulldozerCompatible = false;

    // If modelString != null, the cpu is considered correctly identified.
    protected static String modelString = null;

    public boolean IsK6Compatible(){ return isK6Compatible; }

    public boolean IsK6_2_Compatible(){ return isK6_2_Compatible; }

    public boolean IsK6_3_Compatible(){ return isK6_3_Compatible; }

    public boolean IsGeodeCompatible(){ return isGeodeCompatible; }

    public boolean IsAthlonCompatible(){ return isAthlonCompatible; }

    public boolean IsAthlon64Compatible(){ return isAthlon64Compatible; }

    public boolean IsBobcatCompatible(){ return isBobcatCompatible; }

	public boolean IsBulldozerCompatible(){ return isBulldozerCompatible; }

	static
	{
		identifyCPU();
	}	

    public String getCPUModelString() throws UnknownCPUException
    {
        if (modelString != null)
            return modelString;
        throw new UnknownCPUException("Unknown AMD CPU; Family="+(CPUID.getCPUFamily() + CPUID.getCPUExtendedFamily())+", Model="+(CPUID.getCPUModel() + CPUID.getCPUExtendedModel()));
    }
    

    private synchronized static void identifyCPU()
    {
    //AMD-family = getCPUFamily()+getCPUExtendedFamily()
    //AMD-model = getCPUModel()+getCPUExtendedModel()
    //i486 class (Am486, 5x86) 
        if(CPUID.getCPUFamily() + CPUID.getCPUExtendedFamily() == 4){
            switch(CPUID.getCPUModel() + CPUID.getCPUExtendedModel()){
                case 3:
                    modelString = "486 DX/2";
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
                case 14:
                    modelString = "Am5x86-WT";
                    break;
                case 15:
                    modelString = "Am5x86-WB";
                    break;
            }
        }
        //i586 class (K5/K6/K6-2/K6-III) 
        if(CPUID.getCPUFamily() + CPUID.getCPUExtendedFamily() == 5){
            isK6Compatible = true;
            switch(CPUID.getCPUModel() + CPUID.getCPUExtendedModel()){
                case 0:
                    modelString = "K5/SSA5";
                    break;
                case 1:
                    modelString = "K5";
                    break;
                case 2:
                    modelString = "K5";
                    break;
                case 3:
                    modelString = "K5";
                    break;
                case 4:
                	isK6Compatible = false;
                	isGeodeCompatible = true;
                	modelString = "Geode GX1/GXLV/GXm";
                	break;
                case 5:
                	isK6Compatible = false;
                	isGeodeCompatible = true;
                	modelString = "Geode GX2/LX";
                	break;
                case 6:
                    modelString = "K6";
                    break;
                case 7:
                    modelString = "K6";
                    break;
                case 8:
                    isK6_2_Compatible = true;
                    modelString = "K6-2";
                    break;
                case 9:
                    isK6_2_Compatible = true;
                    isK6_3_Compatible = true;
                    modelString = "K6-3";
                    break;
                case 13:
                    isK6_2_Compatible = true;
                    modelString = "K6-2+ or K6-III+";
                    break;
            }
        }
        //i686 class (Athlon/Athlon XP/Duron/K7 Sempron) 
        if(CPUID.getCPUFamily() + CPUID.getCPUExtendedFamily() == 6){
            isK6Compatible = true;
            isK6_2_Compatible = true;
            isK6_3_Compatible = true;
            isAthlonCompatible = true;
            switch(CPUID.getCPUModel() + CPUID.getCPUExtendedModel()){
                case 0:
                    modelString = "Athlon (250 nm)";
                    break;
                case 1:
                    modelString = "Athlon (250 nm)";
                    break;
                case 2:
                    modelString = "Athlon (180 nm)";
                    break;
                case 3:
                    modelString = "Duron";
                    break;
                case 4:
                    modelString = "Athlon (Thunderbird)";
                    break;
                case 6:
                    modelString = "Athlon (Palamino)";
                    break;
                case 7:
                    modelString = "Duron (Morgan)";
                    break;
                case 8:
                    modelString = "Athlon (Thoroughbred)";
                    break;
                case 10:
                    modelString = "Athlon (Barton)";
                    break;
            }
        }
        //AMD64 class (A64/Opteron/A64 X2/K8 Sempron/Turion/Second-Generation Opteron/Athlon Neo) 
       if(CPUID.getCPUFamily() + CPUID.getCPUExtendedFamily() == 15){
            isK6Compatible = true;
            isK6_2_Compatible = true;
            isK6_3_Compatible = true;
            isAthlonCompatible = true;
            isAthlon64Compatible = true;
            isX64 = true;
            switch(CPUID.getCPUModel() + CPUID.getCPUExtendedModel()){
                case 4:
                    modelString = "Athlon 64/Mobile XP-M";
                    break;
                case 5:
                    modelString = "Athlon 64 FX Opteron";
                    break;
                case 7:
                    modelString = "Athlon 64 FX (Sledgehammer S939, 130 nm)";
                    break;
                case 8:
                    modelString = "Mobile A64/Sempron/XP-M";
                    break;
                case 11:
                    modelString = "Athlon 64 (Clawhammer S939, 130 nm)";
                    break;
                case 12:
                    modelString = "Athlon 64/Sempron (Newcastle S754, 130 nm)";
                    break;
                case 14:
                    modelString = "Athlon 64/Sempron (Newcastle S754, 130 nm)";
                    break;
                case 15:
                    modelString = "Athlon 64/Sempron (Clawhammer S939, 130 nm)";
                    break;                    
                case 18:
                    modelString = "Sempron (Palermo, 90 nm)";
                    break;
                case 20:
                    modelString = "Athlon 64 (Winchester S754, 90 nm)";
                    break;
                case 23:
                    modelString = "Athlon 64 (Winchester S939, 90 nm)";
                    break;
                case 24:
                    modelString = "Mobile A64/Sempron/XP-M (Winchester S754, 90 nm)";
                    break;
                case 26:
                    modelString = "Athlon 64 (Winchester S939, 90 nm)";
                    break;
                case 27:
                    modelString = "Athlon 64/Sempron (Winchester/Palermo 90 nm)";
                    break;
                case 28:
                    modelString = "Sempron (Palermo, 90 nm)";
                    break;
                case 31:
                    modelString = "Athlon 64/Sempron (Winchester/Palermo, 90 nm)";
                    break;
                case 33:
                    modelString = "Dual-Core Opteron (Italy-Egypt S940, 90 nm)";
                    break;
                case 35:
                    modelString = "Athlon 64 X2/A64 FX/Opteron (Toledo/Denmark S939, 90 nm)";
                    break;
                case 36:
                    modelString = "Mobile A64/Turion (Lancaster/Richmond/Newark, 90 nm)";
                    break;
                case 37:
                    modelString = "Opteron (Troy/Athens S940, 90 nm)";
                    break;
                case 39:
                    modelString = "Athlon 64 (San Diego, 90 nm)";
                    break;
                case 43:
                    modelString = "Athlon 64 X2 (Manchester, 90 nm)";
                    break;
                case 44:
                    modelString = "Sempron/mobile Sempron (Palermo/Albany/Roma S754, 90 nm)";
                    break;
                case 47:
                    modelString = "Athlon 64/Sempron (Venice/Palermo S939, 90 nm)";
                    break;
                case 65:
                    modelString = "Second-Generaton Opteron (Santa Rosa S1207, 90 nm)";
                    break;
                case 67:
                    modelString = "Athlon 64 X2/2nd-gen Opteron (Windsor/Santa Rosa, 90 nm)";
                    break;
                case 72:
                    modelString = "Athlon 64 X2/Turion 64 X2 (Windsor/Taylor/Trinidad, 90 nm)";
                    break;
                case 75:
                    modelString = "Athlon 64 X2 (Windsor, 90 nm)";
                    break;
                case 76:
                    modelString = "Mobile A64/mobile Sempron/Turion (Keene/Trinidad/Taylor, 90 nm)";
                    break;
                case 79:
                    modelString = "Athlon 64/Sempron (Orleans/Manila AM2, 90 nm)";
                    break;
                case 93:
                    modelString = "Opteron Gen 2 (Santa Rosa, 90 nm)";
                    break;
                case 95:
                    modelString = "A64/Sempron/mobile Sempron (Orleans/Manila/Keene, 90 nm)";
                    break;
                case 104:
                    modelString = "Turion 64 X2 (Tyler S1, 65 nm)";
                    break;
                case 107:
                    modelString = "Athlon 64 X2/Sempron X2/Athlon Neo X2 (Brisbane/Huron, 65 nm)";
                    break;
                case 108:
                    modelString = "A64/Athlon Neo/Sempron/Mobile Sempron (Lima/Huron/Sparta/Sherman, 65 nm)";
                    break;
                case 111:
                    modelString = "Neo/Sempron/mobile Sempron (Huron/Sparta/Sherman, 65 nm)";
                    break;
                case 124:
                    modelString = "Athlon/Sempron/mobile Sempron (Lima/Sparta/Sherman, 65 nm)";
                    break;
                case 127:
                    modelString = "A64/Athlon Neo/Sempron/mobile Sempron (Lima/Huron/Sparta/Sherman, 65 nm)";
                    break;
                case 193:
                    modelString = "Athlon 64 FX (Windsor S1207 90 nm)";
                    break;
                default: // is this safe?
                    modelString = "Athlon 64 (unknown)";
                    break;
            }
        }
        //Stars (Phenom II/Athlon II/Third-Generation Opteron/Opteron 4100 & 6100/Sempron 1xx) 
        if(CPUID.getCPUFamily() + CPUID.getCPUExtendedFamily() == 16){
            isK6Compatible = true;
            isK6_2_Compatible = true;
            isK6_3_Compatible = true;
            isAthlonCompatible = true;
            isAthlon64Compatible = true;
            isX64 = true;
            switch(CPUID.getCPUModel() + CPUID.getCPUExtendedModel()){
                case 2:
                    modelString = "Phenom / Athlon / Opteron Gen 3 (Barcelona/Agena/Toliman/Kuma, 65 nm)";
                    break;
                case 4:
                    modelString = "Phenom II / Opteron Gen 3 (Shanghai/Deneb/Heka/Callisto, 45 nm)";
                    break;
                case 5:
                    modelString = "Athlon II X2/X3/X4 (Regor/Rana/Propus AM3, 45 nm)";
                    break;
                case 6:
                    modelString = "Mobile Athlon II/Turion II/Phenom II/Sempron/V-series (Regor/Caspian/Champlain, 45 nm)";
                    break;
                case 8:
                    modelString = "Six-Core Opteron/Opteron 4100 series (Istanbul/Lisbon, 45 nm)";
                    break;
                case 9:
                    modelString = "Opteron 6100 series (Magny-Cours G34, 45 nm)";
                    break;
                case 10:
                    modelString = "Phenom II X4/X6 (Zosma/Thuban AM3, 45 nm)";
                    break;
            }
        }
        //K8 mobile+HT3 (Turion X2/Athlon X2/Sempron) 
        if(CPUID.getCPUFamily() + CPUID.getCPUExtendedFamily() == 17){
            isK6Compatible = true;
            isK6_2_Compatible = true;
            isK6_3_Compatible = true;
            isAthlonCompatible = true;
            isAthlon64Compatible = true;
            isX64 = true;
            switch(CPUID.getCPUModel() + CPUID.getCPUExtendedModel()){
                case 3:
                    modelString = "AMD Turion X2/Athlon X2/Sempron (Lion/Sable, 65 nm)";
                    break;
            }
        }
        //Bobcat
        if(CPUID.getCPUFamily() + CPUID.getCPUExtendedFamily() == 20){
            isK6Compatible = true;
            isK6_2_Compatible = true;
            isK6_3_Compatible = true;
            isAthlonCompatible = true;
            isAthlon64Compatible = true;
            isBobcatCompatible = true;
            isX64 = true;
            switch(CPUID.getCPUModel() + CPUID.getCPUExtendedModel()){
                case 1:                    
                    modelString = "Bobcat APU";
                    break;
                // Case 3 is uncertain but most likely a Bobcat APU
                case 3:
                    modelString = "Bobcat APU";
                    break;
            }
        }
        //Bulldozer
        if(CPUID.getCPUFamily() + CPUID.getCPUExtendedFamily() == 21){
            isK6Compatible = true;
            isK6_2_Compatible = true;
            isK6_3_Compatible = true;
            isAthlonCompatible = true;
            isAthlon64Compatible = true;
            isBobcatCompatible = true;
			isBulldozerCompatible = true;
            isX64 = true;
            switch(CPUID.getCPUModel() + CPUID.getCPUExtendedModel()){
                case 1:                    
                    modelString = "Bulldozer FX-6***/FX-8***";
                    break;
            }
        }
    }

	public boolean hasX64()
	{
		return isX64;
	}

}
