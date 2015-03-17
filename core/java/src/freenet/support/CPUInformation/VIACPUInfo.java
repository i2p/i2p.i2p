package freenet.support.CPUInformation;

/**
 *  Moved out of CPUID.java
 *  @since 0.8.7
 */
public interface VIACPUInfo extends CPUInfo{

    /**
     * @return true if the CPU present in the machine is at least an 'c3' CPU
     */
	public boolean IsC3Compatible();
    /**
     * @return true if the CPU present in the machine is at least an 'nano' CPU
     */
	public boolean IsNanoCompatible();
	
	

}
