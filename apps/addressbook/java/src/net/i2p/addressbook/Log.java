/*
 * Copyright (c) 2004 Ragnarok
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package net.i2p.addressbook;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Date;

/**
 * A simple log with automatic time stamping.
 * 
 * @author Ragnarok
 *  
 */
class Log {

    private File file;

    /**
     * Construct a Log instance that writes to the File file.
     * 
     * @param file
     *            A File for the log to write to.
     */
    public Log(File file) {
        this.file = file;
    }

    /**
     * Write entry to a new line in the log, with appropriate time stamp.
     * 
     * @param entry
     *            A String containing a message to append to the log.
     */
    public void append(String entry) {
        BufferedWriter bw = null;
        try {
            bw = new BufferedWriter(new FileWriter(this.file,
                    true));
            String timestamp = new Date().toString();
            bw.write(timestamp + " -- " + entry);
            bw.newLine();
        } catch (IOException exp) {
        } finally {
            if (bw != null)
                try { bw.close(); } catch (IOException ioe) {}
        }
    }

    /**
     * Return the File that the Log is writing to.
     * 
     * @return The File that the log is writing to.
     */
    public File getFile() {
        return this.file;
    }
}
