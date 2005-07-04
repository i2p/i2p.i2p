package net.i2p.router.tunnel;

/** 
 * accept everything 
 */
class DummyValidator implements IVValidator {
    private static final DummyValidator _instance = new DummyValidator();
    public static DummyValidator getInstance() { return _instance; }
    private DummyValidator() {}
    
    public boolean receiveIV(byte ivData[], int ivOffset, byte payload[], int payloadOffset) { return true; }

}