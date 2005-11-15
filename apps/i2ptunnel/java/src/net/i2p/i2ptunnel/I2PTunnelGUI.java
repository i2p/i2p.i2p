/* I2PTunnel is GPL'ed (with the exception mentioned in I2PTunnel.java)
 * (c) 2003 - 2004 mihi
 */
package net.i2p.i2ptunnel;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.TextArea;
import java.awt.TextField;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * AWT gui since kaffe doesn't support swing yet
 */
public class I2PTunnelGUI extends Frame implements ActionListener, Logging {

    TextField input;
    TextArea log;
    I2PTunnel t;

    public I2PTunnelGUI(I2PTunnel t) {
        super("I2PTunnel control panel");
        this.t = t;
        setLayout(new BorderLayout());
        add("South", input = new TextField());
        input.addActionListener(this);
        Font font = new Font("Monospaced", Font.PLAIN, 12);
        add("Center", log = new TextArea("", 20, 80, TextArea.SCROLLBARS_VERTICAL_ONLY));
        log.setFont(font);
        log.setEditable(false);
        log("enter 'help' for help.");
        pack();
        setVisible(true);
    }

    public void log(String s) {
        log.append(s + "\n");
    }

    public void actionPerformed(ActionEvent evt) {
        log("I2PTunnel>" + input.getText());
        t.runCommand(input.getText(), this);
        log("---");
        input.setText("");
    }
}