package net.i2p.router.transport;

/**
 *  For Android MulticastLock.
 *
 *  @since 0.9.41
 */
public interface UPnPScannerCallback {

    /**
     *  Called before a SSDP search begins.
     *  This may be called more than once before afterScan()
     *  if there are multiple searches in parallel.
     */
    public void beforeScan();

    /**
     *  Called after a SSDP search ends.
     *  This will only be called once after the last scan ends.
     */
    public void afterScan();
}
