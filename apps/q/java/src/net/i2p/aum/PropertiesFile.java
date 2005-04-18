/*
 * PropertiesFile.java
 *
 * Created on 20 March 2005, 19:30
 */

package net.i2p.aum;

import java.lang.*;
import java.io.*;
import java.util.*;

/**
 * builds on Properties with methods to load/save directly to/from file
 */
public class PropertiesFile extends Properties {
    
    public String _path;
    public File _file;
    public boolean _fileExists;

    /**
     * Creates a new instance of PropertiesFile
     * @param path Absolute pathname of file where properties are to be stored
     */
    public PropertiesFile(String path) throws IOException {
        super();
        _path = path;
        _file = new File(path);
        _fileExists = _file.isFile();

        if (_file.canRead()) {
            loadFromFile();
        }
    }

    /**
     * Creates new PropertiesFile, updating its content with the
     * keys/values in given hashtable
     * @param path absolute pathname where properties file is located in filesystem
     * @param h instance of Hashtable (or subclass). its content 
     * will be written to this object (note that string representations of keys/vals
     * will be used)
     */
    public PropertiesFile(String path, Hashtable h) throws IOException
    {
        this(path);
        Enumeration keys = h.keys();
        Object key;
        while (true)
        {
            try {
                key = keys.nextElement();
            } catch (NoSuchElementException e) {
                break;
            }
            setProperty(key.toString(), h.get(key).toString());
        }
    }
    
    /**
     * Loads this object from the file
     */
    public void loadFromFile() throws IOException, FileNotFoundException {
        if (_file.canRead()) {
            InputStream fis = new FileInputStream(_file);
            load(fis);
        }
    }

    /**
     * Saves this object to the file
     */
    public void saveToFile() throws IOException, FileNotFoundException {

        if (!_fileExists) {
            _file.createNewFile();
            _fileExists = true;
        }
        OutputStream fos = new FileOutputStream(_file);
        store(fos, null);
    }
    
    /**
     * Stores attribute
     */
    public Object setProperty(String key, String value) {
        Object o = super.setProperty(key, value);
        try {
            saveToFile();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return o;
    }
    
    /**
     * return a property as an int, fall back on default if not found or invalid
     */
    public int getIntProperty(String key, int dflt) {
        try {
            return new Integer((String)getProperty(key)).intValue();
        } catch (Exception e) {
            setIntProperty(key, dflt);
            return dflt;
        }
    }

    /**
     * return a property as an int
     */
    public int getIntProperty(String key) {
        return new Integer((String)getProperty(key)).intValue();
    }

    /**
     * set a property as an int
     */
    public void setIntProperty(String key, int value) {
        setProperty(key, String.valueOf(value));
    }

    /**
     * return a property as a long, fall back on default if not found or invalid
     */
    public long getIntProperty(String key, long dflt) {
        try {
            return new Long((String)getProperty(key)).longValue();
        } catch (Exception e) {
            setLongProperty(key, dflt);
            return dflt;
        }
    }

    /**
     * return a property as an int
     */
    public long getLongProperty(String key) {
        return new Long((String)getProperty(key)).longValue();
    }

    /**
     * set a property as an int
     */
    public void setLongProperty(String key, long value) {
        setProperty(key, String.valueOf(value));
    }

    /**
     * return a property as a float
     */
    public double getFloatProperty(String key) {
        return new Float((String)getProperty(key)).floatValue();
    }

    /**
     * return a property as a float, fall back on default if not found or invalid
     */
    public double getFloatProperty(String key, float dflt) {
        try {
            return new Float((String)getProperty(key)).floatValue();
        } catch (Exception e) {
            setFloatProperty(key, dflt);
            return dflt;
        }
    }

    /**
     * set a property as a float
     */
    public void setFloatProperty(String key, float value) {
        setProperty(key, String.valueOf(value));
    }

    /**
     * return a property as a double
     */
    public double getDoubleProperty(String key) {
        return new Double((String)getProperty(key)).doubleValue();
    }

    /**
     * return a property as a double, fall back on default if not found
     */
    public double getDoubleProperty(String key, double dflt) {
        try {
            return new Double((String)getProperty(key)).doubleValue();
        } catch (Exception e) {
            setDoubleProperty(key, dflt);
            return dflt;
        }
    }

    /**
     * set a property as a double
     */
    public void setDoubleProperty(String key, double value) {
        setProperty(key, String.valueOf(value));
    }

    /**
     * increment an integer property value
     */
    public void incrementIntProperty(String key) {
        setIntProperty(key, getIntProperty(key)+1);
    }

}

