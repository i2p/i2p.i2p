package net.i2p.aum;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

/**
 * SimpleFile - subclass of File which adds some python-like
 * methods. Cuts out a lot of the red tape involved with reading
 * from and writing to files
 */
public class SimpleFile_old extends File {

    public FileReader _reader;
    public FileWriter _writer;
    
    public SimpleFile_old(String path) {
    
        super(path);
        
        _reader = null;
        _writer = null;
    }
    
    /**
     * Reads all remaining content from the file
     * @return the content as a String
     * @throws IOException
     */
    public String read() throws IOException {
    
        return read((int)length());
    }
    
    /**
     * Reads one or more bytes of data from the file
     * @return the content as a String
     * @throws IOException
     */
    public String read(int nbytes) throws IOException {
    
        // get a reader, if we don't already have one
        if (_reader == null) {
            _reader = new FileReader(this);
        }
    
        char [] cbuf = new char[nbytes];
    
        int nread = _reader.read(cbuf);
    
        if (nread == 0) {
            return "";
        }
    
        return new String(cbuf, 0, nread);
            
    }
    
    /**
     * Writes one or more bytes of data to a file
     * @param buf a String containing the data to write
     * @return the number of bytes written, as an int
     * @throws IOException
     */
    public int write(String buf) throws IOException {
    
        // get a reader, if we don't already have one
        if (_writer == null) {
            _writer = new FileWriter(this);
        }
    
        _writer.write(buf);
        _writer.flush();
        return buf.length();
    }

    public int write(byte [] buf) throws IOException {

        return write(new String(buf));
    }

    /**
     * convenient one-hit write
     * @param path pathname of file to write to
     * @param buf data to write
     */
    public static int write(String path, String buf) throws IOException {
        SimpleFile_old f = new SimpleFile_old(path);
        return f.write(buf);
    }

    /**
     * tests if argument refers to an actual file
     * @param path pathname to test
     * @return true if a file, false if not
     */
    public static boolean isFile(String path) {
        return new File(path).isFile();
    }
    
    /**
     * tests if argument refers to a directory
     * @param path pathname to test
     * @return true if a directory, false if not
     */
    public static boolean isDir(String path) {
        return new File(path).isDirectory();
    }
    
    /**
     * tests if a file or directory exists
     * @param path pathname to test
     * @return true if exists, or false
     */
    public static boolean exists(String path) {
        return new File(path).exists();
    }
    
}

