package net.i2p.app;

/**
 *  The callback when a user clicks a MenuHandle.
 *
 *  @since 0.9.59
 */
public interface MenuCallback {

    /**
     *  Called when the user clicks the menu
     *
     *  @param menu the menu handle clicked
     */
    public void clicked(MenuHandle menu);
}
