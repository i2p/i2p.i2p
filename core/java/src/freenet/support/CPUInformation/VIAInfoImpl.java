package freenet.support.CPUInformation;

class VIAInfoImpl extends CPUIDCPUInfo implements VIACPUInfo {
	
    protected static boolean isC3Compatible = false;
    protected static boolean isNanoCompatible = false;

    // If modelString != null, the cpu is considered correctly identified.
    protected static String modelString = null;

    
	@Override
    public boolean IsC3Compatible(){ return isC3Compatible; }
	@Override
	public boolean IsNanoCompatible(){ return isNanoCompatible; }
	
	static
	{
		identifyCPU();
	}
	
    @Override
    public String getCPUModelString()
    {
        if (modelString != null)
            return modelString;
        throw new UnknownCPUException("Unknown VIA CPU; Family="+(CPUID.getCPUFamily() + CPUID.getCPUExtendedFamily())+", Model="+(CPUID.getCPUModel() + CPUID.getCPUExtendedModel()));
    }


	@Override
	public boolean hasX64()
	{
		return false;
	}
	

	private synchronized static void identifyCPU()
	{
        if(CPUID.getCPUFamily() == 6){
        	isC3Compatible = true; // Possibly not optimal
            switch(CPUID.getCPUModel()){
            	case 5:
            		modelString = "Cyrix M2";
            		break;
            	case 6:
            		modelString = "C5 A/B";
            		break;
            	case 7:
            		modelString = "C5 C";
            		break;
            	case 8:
            		modelString = "C5 N";
            		break;
            	case 9:
            		modelString = "C5 XL/P";
            		break;
            	case 10:
            		modelString = "C5 J";
            		break;
            	case 15:
            		isNanoCompatible = true;
            		modelString = "Nano";
            		break;
            }
        }
	}
}
