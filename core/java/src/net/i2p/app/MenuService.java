package net.i2p.app;

/**
 *  A service to provide a menu to users.
 *  This service is currently provided by desktopgui (when supported and enabled).
 *  Other applications may support this interface in the future.
 *
 *  This API is independent of any particular UI framework, e.g. AWT or Swing.
 *
 *  Example usage:
 *
 * <pre>
 *     ClientAppManager cmgr = _context.clientAppManager();
 *     if (cmgr != null) {
 *         MenuService ms = (MenuService) cmgr.getRegisteredApp("desktopgui");
 *         if (ms != null)
 *             ms.addMenuHandle(_t("foo"), new Callback());
 *     }
 * </pre>
 *
 *  @since 0.9.59
 */
public interface MenuService {

    /**
     *  Menu will start out shown and enabled, in the root menu
     *
     *  @param message for the menu, translated
     *  @param callback fired on click
     *  @return null on error
     */
    public MenuHandle addMenu(String message, MenuCallback callback);

    /**
     *  Menu will start out enabled, as a submenu
     *
     *  @param message for the menu, translated
     *  @param callback fired on click
     *  @param parent the parent menu this will be a submenu of, or null for top level
     *  @return null on error
     */
    public MenuHandle addMenu(String message, MenuCallback callback, MenuHandle parent);

    public void removeMenu(MenuHandle item);

    public void showMenu(MenuHandle item);

    public void hideMenu(MenuHandle item);

    public void enableMenu(MenuHandle item);

    public void disableMenu(MenuHandle item);

    public void updateMenu(String message, MenuHandle item);
}
