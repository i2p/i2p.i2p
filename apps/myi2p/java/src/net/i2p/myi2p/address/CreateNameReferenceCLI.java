package net.i2p.myi2p.address;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import java.util.Iterator;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.data.Destination;
import net.i2p.data.PrivateKey;
import net.i2p.data.SigningPrivateKey;
import net.i2p.util.Log;

/**
 * CreateNameReferenceCLI outputFile privateDestFile preferredName sequenceNum serviceType[ key=value]*
 */
public class CreateNameReferenceCLI {
    private I2PAppContext _context;
    private String _args[];
    private String _outputFile;
    private String _destFile;
    private String _preferredName;
    private long _sequenceNum;
    private String _serviceType;
    private Properties _options;
    
    public CreateNameReferenceCLI(String[] args) {
        _context = new I2PAppContext();
        _args = args;
        _options = new Properties();
    }
    
    public void execute() {
        if (parseArgs())
            doExecute();
        else
            System.err.println("Usage: CreateNameReferenceCLI outputFile privateDestFile preferredName sequenceNum serviceType[ key=value]*");
    }
    
    private boolean parseArgs() {
        if ( (_args == null) || (_args.length < 4) ) 
            return false;
        _outputFile = _args[0];
        _destFile = _args[1];
        _preferredName = _args[2];
        try {
            _sequenceNum = Long.parseLong(_args[3]);
        } catch (NumberFormatException nfe) {
            return false;
        }
        _serviceType = _args[4];
        
        for (int i = 5; i < _args.length; i++) {
            int eq = _args[i].indexOf('=');
            if ( (eq <= 0) || (eq >= _args[i].length() - 1) )
                continue;
            String key = _args[i].substring(0,eq);
            String val = _args[i].substring(eq+1);
            _options.setProperty(key, val);
        }
        return true;
    }
    
    private void doExecute() {
        Destination dest = null;
        SigningPrivateKey priv = null;
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(_destFile);
            dest = new Destination();
            dest.readBytes(fis);
            PrivateKey whocares = new PrivateKey();
            whocares.readBytes(fis);
            priv = new SigningPrivateKey();
            priv.readBytes(fis);
        } catch (Exception e) {
            System.err.println("Destination private keys under " + _destFile + " are corrupt");
            e.printStackTrace();
            return;
        } finally {
            if (fis != null) try { fis.close(); } catch (IOException ioe) {}
        }
        
        NameReference ref = new NameReference(_context);
        ref.setDestination(dest);
        ref.setPreferredName(_preferredName);
        ref.setSequenceNum(_sequenceNum);
        ref.setServiceType(_serviceType);
        if (_options != null) {
            for (Iterator iter = _options.keySet().iterator(); iter.hasNext(); ) {
                String key = (String)iter.next();
                String val = _options.getProperty(key);
                ref.setOption(key, val);
            }
        }
        
        try {
            ref.sign(priv);
        } catch (IllegalStateException ise) {
            System.err.println("Error signing the new reference");
            ise.printStackTrace();
        }
        
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(_outputFile);
            ref.write(fos);
        } catch (IOException ioe) {
            System.err.println("Error writing out the new reference");
            ioe.printStackTrace();
        } finally {
            if (fos != null) try { fos.close(); } catch (IOException ioe) {}
        }
        
        System.out.println("Reference created at " + _outputFile);
    }
    
    public static void main(String args[]) {
        CreateNameReferenceCLI cli = new CreateNameReferenceCLI(args);
        cli.execute();
    }
}
