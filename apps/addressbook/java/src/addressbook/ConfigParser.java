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

package addressbook;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.LinkedList;
import java.util.Iterator;
import java.io.*;
import java.net.URL;

/**
 * Utility class providing methods to parse and write files in config file
 * format, and subscription file format.
 * 
 * @author Ragnarok
 */
public class ConfigParser {

    /**
     * Strip the comments from a String. Lines that begin with '#' and ';' are
     * considered comments, as well as any part of a line after a '#'.
     * 
     * @param inputLine
     *            A String to strip comments from.
     * @return A String without comments, but otherwise identical to inputLine.
     */
    public static String stripComments(String inputLine) {
        if (inputLine.startsWith(";")) {
            return "";
        }
        if (inputLine.split("#").length > 0) {
            return inputLine.split("#")[0];
        } else {
            return "";
        }
    }

    /**
     * Return a Map using the contents of BufferedReader input. input must have
     * a single key, value pair on each line, in the format: key=value. Lines
     * starting with '#' or ';' are considered comments, and ignored. Lines that
     * are obviously not in the format key=value are also ignored.
     * 
     * @param input
     *            A BufferedReader with lines in key=value format to parse into
     *            a Map.
     * @return A Map containing the key, value pairs from input.
     * @throws IOException
     *             if the BufferedReader cannot be read.
     *  
     */
    public static Map parse(BufferedReader input) throws IOException {
        Map result = new HashMap();
        String inputLine;
        inputLine = input.readLine();
        while (inputLine != null) {
            inputLine = ConfigParser.stripComments(inputLine);
            String[] splitLine = inputLine.split("=");
            if (splitLine.length == 2) {
                result.put(splitLine[0].trim(), splitLine[1].trim());
            }
            inputLine = input.readLine();
        }
        input.close();
        return result;
    }

    /**
     * Return a Map using the contents of the file at url. See
     * parseBufferedReader for details of the input format.
     * 
     * @param url
     *            A url pointing to a file to parse.
     * @return A Map containing the key, value pairs from url.
     * @throws IOException
     *             if url cannot be read.
     */
    public static Map parse(URL url) throws IOException {
        InputStream urlStream;
        urlStream = url.openConnection().getInputStream();
        BufferedReader br = new BufferedReader(new InputStreamReader(urlStream));
        return ConfigParser.parse(br);
    }

    /**
     * Return a Map using the contents of the File file. See parseBufferedReader
     * for details of the input format.
     * 
     * @param file
     *            A File to parse.
     * @return A Map containing the key, value pairs from file.
     * @throws IOException
     *             if file cannot be read.
     */
    public static Map parse(File file) throws IOException {
        FileInputStream fileStream;
        fileStream = new FileInputStream(file);
        BufferedReader br = new BufferedReader(
                new InputStreamReader(fileStream));
        return ConfigParser.parse(br);
    }

    /**
     * Return a List where each element is a line from the File file.
     * 
     * @param file
     *            A File to parse.
     * @return A List consisting of one element for each line in file.
     * @throws IOException
     *             if file cannot be read.
     */
    public static List parseSubscriptions(File file) throws IOException {
        FileInputStream fileStream = new FileInputStream(file);
        BufferedReader br = new BufferedReader(
                new InputStreamReader(fileStream));
        List result = new LinkedList();
        String inputLine = br.readLine();
        while (inputLine != null) {
            inputLine = ConfigParser.stripComments(inputLine);
            if (inputLine.trim().length() > 0) {
                result.add(inputLine.trim());
            }
            inputLine = br.readLine();
        }
        br.close();
        return result;
    }

    /**
     * Write contents of Map hash to BufferedWriter output. Output is written
     * with one key, value pair on each line, in the format: key=value.
     * 
     * @param hash
     *            A Map to write to output.
     * @param output
     *            A BufferedWriter to write the Map to.
     * @throws IOException
     *             if the BufferedWriter cannot be written to.
     */
    public static void write(Map hash, BufferedWriter output)
            throws IOException {
        Iterator keyIter = hash.keySet().iterator();

        while (keyIter.hasNext()) {
            String key = (String) keyIter.next();
            output.write(key + "=" + (String) hash.get(key));
            output.newLine();
        }
        output.close();
    }

    /**
     * Write contents of Map hash to the file at location. Output is written
     * with one key, value pair on each line, in the format: key=value.
     * 
     * @param hash
     *            A Map to write to file.
     * @param file
     *            A File to write the Map to.
     * @throws IOException
     *             if file cannot be written to.
     */
    public static void write(Map hash, File file) throws IOException {
        ConfigParser.write(hash,
                new BufferedWriter(new FileWriter(file, false)));
    }
}