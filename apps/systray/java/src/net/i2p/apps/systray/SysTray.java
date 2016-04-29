package net.i2p.apps.systray;

import java.lang.reflect.Method;

import net.i2p.util.SystemVersion;

/**
 * A system tray control for launching the I2P router console.
 *
 * Not part of the public API. Works on 32-bit Windows only.
 * To be removed or replaced.
 *
 * @since implementation moved to SysTrayImpl in 0.9.26
 */
public class SysTray {

    /**
     *  Warning - systray4j.jar may not be present - may return null.
     *  As of 0.9.26, uses reflection so we may compile without systray4j.jar.
     *
     *  @return null if not supported, since 0.9.26 (prior to that would throw NoClassDefFoundError)
     *  @since 0.8.1
     */
    public static SysTray getInstance() {
        if (SystemVersion.isWindows() && !SystemVersion.is64Bit())
            return getImpl();
        return null;
    }

    /**
     *  Warning - systray4j.jar may not be present - may return null
     *
     *  @return null if not supported
     *  @since 0.9.26
     */
    private static SysTray getImpl() {
        try {
            Class<?> impl = Class.forName("net.i2p.apps.systray.SysTrayImpl", true, ClassLoader.getSystemClassLoader());
            Method getInstance = impl.getMethod("getInstance");
            return (SysTray) getInstance.invoke(null,(Object[])  null);
        } catch (Throwable t) {
            System.out.println("WARN: Unable to start systray");
            t.printStackTrace();
            return null;
        }
    }

    /**
     *  This mimics what is now in SysTrayImpl.main(),
     *  not that anybody was using that.
     */
    public static void main(String[] args) {
        SysTray impl = getImpl();
        if (impl == null) {
            System.out.println("No systray implementation found");
            System.exit(1);
        }
        System.err.println("Hit ^C to exit");
        Thread t = Thread.currentThread();
        synchronized(t) {
            try {
                t.wait();
            } catch (InterruptedException ie) {}
        }
    }
}
