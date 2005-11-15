/*
 * I2P - An anonymous, secure, and fully-distributed communication network.
 *
 * BrowserChooser.java
 * 2004 The I2P Project
 * http://www.i2p.net
 * This code is public domain.
 */

package net.i2p.apps.systray;

import java.awt.FileDialog;
import java.awt.Frame;

/**
 * A simple file chooser dialog.
 * 
 * @author hypercubus
 */
public class BrowserChooser extends FileDialog {

    public BrowserChooser(Frame owner, String windowTitle) {
        super(owner, windowTitle);
        initialize();
    }

    public void initialize(){
        this.setVisible(true);
    }
}
