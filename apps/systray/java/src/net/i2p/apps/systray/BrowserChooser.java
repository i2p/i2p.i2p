/*
 * I2P - An anonymous, secure, and fully-distributed communication network.
 *
 * BrowserChooser.java
 * 2004 The I2P Project
 * This code is public domain.
 */

package net.i2p.apps.systray;

import java.awt.FileDialog;
import java.awt.Frame;

/**
 * A rather nasty AWT file chooser dialog (thanks, Kaffe!) which allows the user
 * to select their preferred browser with.
 * 
 * @author hypercubus
 */
public class BrowserChooser extends FileDialog {

	public BrowserChooser(Frame owner, String windowTitle) {
		super(owner, windowTitle);
		initialize();
	}

	public void initialize(){
		this.setSize(300,400);
        this.show();
	}
}
