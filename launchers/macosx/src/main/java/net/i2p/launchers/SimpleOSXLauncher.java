package net.i2p.launchers;

import net.i2p.router.Router;

public class SimpleOSXLauncher extends EnvCheck {

    protected Router i2pRouter;

    /**
     *
     * This is bairly a abstraction layer for the Router.
     * Why? I suspect we will add some spesific osx launcher code at startup
     * in the jvm somewhere, and this seem like a nice place to not make a mess everywhere.
     *
     * @author Meeh
     * @since 0.9.35
     */
    public SimpleOSXLauncher(String[] args) {
        super(args);
        i2pRouter = new Router();
    }
    public static void main(String[] args) {
        new SimpleOSXLauncher(args);
    }
}
