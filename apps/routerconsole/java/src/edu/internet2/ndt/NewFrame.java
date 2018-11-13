package edu.internet2.ndt;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Utility class that creates a new "Frame" with a window closing functionality.
 * This Class is used to provide a base "frame" for the Statistics, Details and
 * Options windows
 * 
 * This class is declared separately so that it can be easily extended by users
 * to customize based on individual needs
 * 
 */
public class NewFrame extends JFrame {
	/**
	 * Auto-generated compiler constant that does not contribute to classes'
	 * functionality
	 */
	private static final long serialVersionUID = 8990839319520684317L;

    /**
     * Constructor
     **/
    public NewFrame(final JApplet parent) throws HeadlessException {
		addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent event) {
                parent.requestFocus();
				dispose();
			}
		});
	}

} // class: NewFrame
