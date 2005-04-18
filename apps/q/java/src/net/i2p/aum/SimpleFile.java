package net.i2p.aum;

import java.lang.*;
import java.io.*;
import java.util.*;
import java.net.*;

import net.i2p.data.*;

/**
 * SimpleFile - subclass of File which adds some python-like
 * methods. Cuts out a lot of the red tape involved with reading
 * from and writing to files
 */
public class SimpleFile {

    public RandomAccessFile _file;
    public String _path;

    public SimpleFile(String path, String mode) throws FileNotFoundException {

        _path = path;
        _file = new RandomAccessFile(path, mode);
    }
    
    public byte [] readBytes() throws IOException {
        return readBytes((int)_file.length());
    }

    public byte[] readBytes(int n) throws IOException {
        byte [] buf = new byte[n];
        _file.readFully(buf);
        return buf;
    }

    public char [] readChars() throws IOException {
        return readChars((int)_file.length());
    }

    public char[] readChars(int n) throws IOException {
        char [] buf = new char[n];
        //_file.readFully(buf);
        return buf;
    }

    /**
     * Reads all remaining content from the file
     * @return the content as a String
     * @throws IOException
     */
    public String read() throws IOException {

        return read((int)_file.length());
    }
    
    /**
     * Reads one or more bytes of data from the file
     * @return the content as a String
     * @throws IOException
     */
    public String read(int nbytes) throws IOException {
    
        return new String(readBytes(nbytes));
    }
    
    /**
     * Writes one or more bytes of data to a file
     * @param buf a String containing the data to write
     * @return the number of bytes written, as an int
     * @throws IOException
     */
    public int write(String buf) throws IOException {

        return write(buf.getBytes());
    }

    public int write(byte [] buf) throws IOException {

        _file.write(buf);
        return buf.length;
    }

    /**
     * convenient one-hit write
     * @param path pathname of file to write to
     * @param buf data to write
     */
    public static int write(String path, String buf) throws IOException {
        return new SimpleFile(path, "rws").write(buf);
    }

    /**
     * tests if argument refers to an actual file
     * @param path pathname to test
     * @return true if a file, false if not
     */
    public boolean isFile() {
        return new File(_path).isFile();
    }
    
    /**
     * tests if argument refers to a directory
     * @param path pathname to test
     * @return true if a directory, false if not
     */
    public boolean isDir() {
        return new File(_path).isDirectory();
    }
    
    /**
     * tests if a file or directory exists
     * @param path pathname to test
     * @return true if exists, or false
     */
    public boolean exists() {
        return new File(_path).exists();
    }
    
}

