package net.i2p.router.web;


public class ConfigConsoleHelper extends HelperBase {
    private String consolePassword="consolePassword";

    public ConfigConsoleHelper() {}
    
    public String getSettings() {
        StringBuilder buf = new StringBuilder(4*1024);
        buf.append(consolePassword);
        return buf.toString();
    }
}
