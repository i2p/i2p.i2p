package net.i2p.router.peermanager;


/**
 * Provide a means of quantifying a profiles fitness in some particular aspect, as well
 * as to coordinate via statics the four known aspects.
 *
 */
class Calculator {
    private static Calculator _isFailingCalc = new IsFailingCalculator();
    private static Calculator _integrationCalc = new IntegrationCalculator();
    private static Calculator _speedCalc = new SpeedCalculator();
    private static Calculator _reliabilityCalc = new ReliabilityCalculator();
    
    public static Calculator getIsFailingCalculator() { return _isFailingCalc; }
    public static Calculator getIntegrationCalculator() { return _integrationCalc; }
    public static Calculator getSpeedCalculator() { return _speedCalc; } 
    public static Calculator getReliabilityCalculator() { return _reliabilityCalc; }
    
    /**
     * Evaluate the profile according to the current metric
     */
    public double calc(PeerProfile profile) { return 0.0d; }
    /**
     * Evaluate the profile according to the current metric
     */
    public boolean calcBoolean(PeerProfile profile) { return true; }
}
