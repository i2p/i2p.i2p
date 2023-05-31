package net.i2p.app;

/**
 *  An opaque handle for the menu, returned from MenuService.addMenuHandle()
 *
 *  @since 0.9.59
 */
public interface MenuHandle {

    /**
     *  @return a unique identifier for this MenuHandle
     */
    public int getID();

}
