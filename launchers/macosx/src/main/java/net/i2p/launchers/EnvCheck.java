package net.i2p.launchers;

/**
 * Both the extractor class and the launcher class needs to be able to verify
 * the environment (variables) to work. This is a base class implementing it.
 *
 * @author Meeh
 * @since 0.9.35
 */
public class EnvCheck {

    protected String baseDirPath = null;

    protected boolean isBaseDirectorySet() {
        baseDirPath = System.getProperty("i2p.base.dir");
        if (baseDirPath == null) {
            baseDirPath = System.getenv("I2PBASE");
        }
        return (baseDirPath != null);
    }

    public EnvCheck(String[] args) {
        if (!isBaseDirectorySet()) {
            throw new RuntimeException("Can't detect I2P's base directory!");
        }
    }
}
