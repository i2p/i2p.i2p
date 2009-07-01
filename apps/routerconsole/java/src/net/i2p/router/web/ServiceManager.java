/*
 * I2P - An anonymous, secure, and fully-distributed communication network.
 * 
 * ServiceManager.java
 * 2004 The I2P Project
 * http://www.i2p.net
 * This code is public domain.
 */

package net.i2p.router.web;

//import java.io.InputStream;

import net.i2p.util.ShellCommand;

/**
 * Handles installation and uninstallation of I2P as a service.
 * 
 * @author hypercubus
 */
public class ServiceManager {

    private static final boolean IS_WINDOWS = System.getProperty("os.name").startsWith("Windows") ? true : false;

    private ShellCommand _shellCommand = new ShellCommand();

    /**
     * Invokes the service wrapper installation script via a shell process.
     * 
     * @return <code>null</code> if the installation was successful, otherwise
     *         a <code>String</code> containing the shell output including error
     *         messages is returned.
     */
    public String installService() {
        return exec("install_i2p_service_" + (IS_WINDOWS ? "winnt.bat" : "unix"));
    }

    /**
     * Invokes the service wrapper uninstallation script via a shell process.
     * 
     * @return <code>null</code> if the uninstallation was successful, otherwise
     *         a <code>String</code> containing the shell output including error
     *         messages is returned.
     */
    public String uninstallService() {
        return exec("uninstall_i2p_service_" + (IS_WINDOWS ? "winnt.bat" : "unix"));
    }

    private String exec(String command) {

//        InputStream  StdoutStream = _shellCommand.getInputStream();
//        InputStream  StderrStream = _shellCommand.getErrorStream();
        StringBuilder result       = null;

        if (_shellCommand.executeAndWait(command))
            return null;

        else
            if (result.toString().equals(""))
                return null;

            else
                return result.toString();
    }
}
