/*
 * QIndexFile.java
 *
 * Created on March 24, 2005, 11:55 AM
 */

package net.i2p.aum.q;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Date;
import java.util.Iterator;

/**
 * <p>Implements a binary-searchable file for storing (time, hash) records.
 * This makes it faster for server nodes to determine which content entries,
 * catalog entries and peer entries have appeared since time t.</p>
 *
 * <p>To ease inter-operation with other programs, as well as human troubleshooting,
 * The file is implemented as a plain text file, with records in the
 * following format:
 *   <ul>
 *     <li><b>time</b> unixtime, as 10-byte decimal string</li>
 *     <li><b>=</b> single-char delimiter</li>
 *     <li><b>hash</b> - a 44-byte Base64 representation of an sha256 hash</li>
 *   </ul>
 * </p>
 */
public class QIndexFile {
    
    public String path;
    File fileObj;
    RandomAccessFile file;
    public long rawLength;
    public int numRecs;
    FileReader reader;
    FileWriter writer;
    
    /** length of base64 representation of sha256 hash */
    static public int hashLen = 43;

    /** length of unixtime milliseconds in decimal format */
    static public int timeLen = 13;

    /**
     * length of records, allowing for time field, delimter (,),
     * hash field and terminating newline
     */
    static public int recordLen = hashLen + timeLen + 2;
    
    /**
     * Create a new index file
     * @param path absolute pathname on filesystem
     */
    public QIndexFile(String path) throws IOException {
        this.path = path;
        fileObj = new File(path);

        // if file doesn't exist, ensure parent dir exists, so subsequent
        // file creation will (hopefully) succeed
        if (!fileObj.exists())
        {
            // create parent directory if not already existing
            String parentDir = fileObj.getParent();
            File parentFile = new File(parentDir);
            if (!parentFile.isDirectory())
            {
                parentFile.mkdirs();
            }
        }
        
        // get a random access object, creating file if not yet existing
        file = new RandomAccessFile(fileObj, "rws");

        // barf if file's length is not a multiple of record length
        rawLength = file.length();
        if (rawLength % recordLen != 0) {
            throw new IOException("File size not a multiple of record length ("+recordLen+")");
        }
        
        // note record count
        numRecs = (int)(rawLength / recordLen);
    }

    /**
     * fetch an iterator for items after a given time
     */
    public synchronized Iterator getItemsSince(int time) throws IOException
    {
        //System.out.println("getItemsSince: time="+time);

        // if no records, return an empty iterator
        if (numRecs == 0)
        {
            return new QIndexFileIterator(this, 0);
        }
        
        // otherwise, binary search till we find an item time-stamped
        // after given time
        long mtime = ((long)time) * 1000;
        int lo = 0;
        int hi = numRecs;
        int lastguess = -1;
        while (hi - lo > 0)
        {
            int guess = (hi + lo) / 2;
            //System.out.println("getItemsSince: lo="+lo+" guess="+guess+" hi="+hi);
            if (guess == lastguess) // && hi - lo == 1)
            {
                break;
            }
            lastguess = guess;

            Object [] rec = getRecord(guess);
            long t = ((Long)rec[0]).longValue();
            if (t <= mtime)
            {
                // guess too low, go for upper range
                lo = guess;
                continue;
            }
            else
            {
                // guess too high, pick lower range
                hi = guess;
                continue;
            }
        }

        // found
        return new QIndexFileIterator(this, hi);
    }

    /**
     * adds a new base64 hash value record, saving it with current time
     */
    public synchronized void add(String h) throws IOException
    {
        // barf if hash is incorrect length
        if (h.length() != hashLen)
        {
            System.out.println("hash="+h);
            throw new IOException("Incorrect hash length ("+h.length()+"), should be "+hashLen);
        }
        
        // format current date/time as decimal string, pad with leading zeroes
        Date d = new Date();
        String ds = String.valueOf(d.getTime());
        while (ds.length() < timeLen)
        {
            ds = "0" + ds;
        }

        // now can construct record
        String rec = ds + "," + h + "\n";

        // append it to file
        file.seek(numRecs * recordLen);
        file.writeBytes(rec);
        
        // and update count
        numRecs += 1;
        rawLength += recordLen;
    }

    public long getRecordTime(int n) throws IOException
    {
        Object [] rec = getRecord(n);
        
        return ((Long)rec[0]).longValue();
    }

    /** return number of records currently within file */
    public int length()
    {
        return numRecs;
    }

    /**
     * returns the hash field of record n
     */
    public String getRecordHash(int n) throws IOException
    {
        Object [] rec = getRecord(n);
        return (String)rec[1];
    }

    public synchronized Object [] getRecord(int n) throws IOException
    {
        Object [] rec = new Object[2];

        String recStr = getRecordStr(n);
        String [] flds = recStr.split(",");
        Long t = new Long(flds[0]);
        String h = flds[1];
        rec[0] = t;
        rec[1] = h;
        return rec;
    }
    
    protected synchronized String getRecordStr(int n) throws IOException
    {
        // barf if over or under-reaching
        if (n < 0 || n > numRecs - 1)
        {
            throw new IOException("Record number ("+n+") out of range");
        }

        // position to location of the record
        file.seek(n * recordLen);

        // read, trim and return
        return file.readLine().trim();
    }

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        try {
            QIndexFile q = new QIndexFile("/home/david/.quartermaster_client/content/index.dat");
            Iterator i = q.getItemsSince((int)(new Date().getTime() / 1000));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
