package net.i2p.data;


import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

import net.i2p.I2PException;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;


public class PrivateKeyFile {
    public PrivateKeyFile(File file, I2PClient client) {
        this.file = file;
        this.client = client;
        this.dest = null;
    }
    
    
    public void createIfAbsent() throws I2PException, IOException {
        if(!this.file.exists()) {
            FileOutputStream out = new FileOutputStream(this.file);
            this.dest = this.client.createDestination(out);
            out.close();
        }
    }
    
    public Destination getDestination() {
        // TODO: how to load destination if this is an old key?
        return dest;
    }
    
    public I2PSession open() throws I2PSessionException, IOException {
        return this.open(new Properties());
    }
    public I2PSession open(Properties opts) throws I2PSessionException, IOException {
        // open input file
        FileInputStream in = new FileInputStream(this.file);
        
        // create sesssion
        I2PSession s = this.client.createSession(in, opts);
        
        // close file
        in.close();
        
        return s;
    }
    
    
    
    
    private File file;
    private I2PClient client;
    private Destination dest;
}