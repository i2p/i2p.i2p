/*
 * Created on Jul 17, 2004
 *
 */
package freenet.support.CPUInformation;

/**
 * @author Iakin
 * An interface for classes that provide lowlevel information about AMD CPU's
 *
 * free (adj.): unencumbered; not under the control of others
 * Written by Iakin in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might. Use at your own risk.
 */
public interface AMDCPUInfo extends CPUInfo {
    /**
     * @return true iff the CPU present in the machine is at least an 'k6' CPU
     */
    public boolean IsK6Compatible();
    /**
     * @return true iff the CPU present in the machine is at least an 'k6-2' CPU
     */
    public boolean IsK6_2_Compatible();
    /**
     * @return true iff the CPU present in the machine is at least an 'k6-3' CPU
     */
    public boolean IsK6_3_Compatible();

    /**
     * @return true iff the CPU present in the machine is at least an 'k7' CPU (Atlhon, Duron etc. and better)
     */
    public boolean IsAthlonCompatible();
    /**
     * @return true iff the CPU present in the machine is at least an 'k8' CPU (Atlhon 64, Opteron etc. and better)
     */
    public boolean IsAthlon64Compatible();
}
