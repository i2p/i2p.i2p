/*
 * Created on Jul 17, 2004
 *
 * free (adj.): unencumbered; not under the control of others
 * Written by Iakin in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might. Use at your own risk.
 */
package freenet.support.CPUInformation;

/**
 * An interface for classes that provide lowlevel information about AMD CPU's
 *
 * @author Iakin
 */
public interface AMDCPUInfo extends CPUInfo {
    /**
     * @return true if the CPU present in the machine is at least an 'k6' CPU
     */
    public boolean IsK6Compatible();
    /**
     * @return true if the CPU present in the machine is at least an 'k6-2' CPU
     */
    public boolean IsK6_2_Compatible();
    /**
     * @return true if the CPU present in the machine is at least an 'k6-3' CPU
     */
    public boolean IsK6_3_Compatible();
    /**
     * @return true if the CPU present in the machine is at least an 'geode' CPU
     */
    boolean IsGeodeCompatible();
    /**
     * @return true if the CPU present in the machine is at least an 'k7' CPU (Atlhon, Duron etc. and better)
     */
    public boolean IsAthlonCompatible();
    /**
     * @return true if the CPU present in the machine is at least an 'k8' CPU (Atlhon 64, Opteron etc. and better)
     */
    public boolean IsAthlon64Compatible();
    /**
     * @return true if the CPU present in the machine is at least an 'k8' CPU (Atlhon 64, Opteron etc. and better)
     */
	public boolean IsBobcatCompatible();
    /**
     * @return true if the CPU present in the machine is at least a 'bulldozer' CPU
     */
	public boolean IsBulldozerCompatible();
}
