package net.i2p.desktopgui.util;

import javax.swing.InputVerifier;
import javax.swing.JComponent;
import javax.swing.JTextField;

/**
 *
 * @author mathias
 */

public class IntegerVerifier extends InputVerifier {

    @Override
    public boolean verify(JComponent arg0) {
        JTextField jtf = (JTextField) arg0;
        return verify(jtf.getText());
    }

    @Override
    public boolean shouldYieldFocus(JComponent input) {
        return verify(input);
    }
    
    public static boolean verify(String s) {
        for(int i=0;i<s.length();i++)
            if(s.charAt(i) > '9' || s.charAt(i) < '0')
                return false;
        return true;
    }
    
}