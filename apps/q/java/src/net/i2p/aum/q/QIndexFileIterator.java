/*
 * QIndexFileIterator.java
 *
 * Created on March 24, 2005, 1:49 PM
 */

package net.i2p.aum.q;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Implements an Iterator for index files
 */
public class QIndexFileIterator implements Iterator
{
    public QIndexFile file;
    int recNum;

    /** Creates an iterator starting from beginning of index file */
    public QIndexFileIterator(QIndexFile qif)
    {
        this(qif, 0);
    }
    
    /** Creates a new instance of QIndexFileIterator */
    public QIndexFileIterator(QIndexFile qif, int recNum)
    {
        file = qif;
        this.recNum = recNum;
    }
    
    public boolean hasNext()
    {
        return recNum < file.length();
    }
    
    public Object next() throws NoSuchElementException
    {
        String rec;
        try {
            rec = file.getRecordHash(recNum);
        }
        catch (Exception e) {
            throw new NoSuchElementException("Reached end of index");
        }
        recNum += 1;
        return rec;
    }
    
    public void remove()
    {
    }
    
}

