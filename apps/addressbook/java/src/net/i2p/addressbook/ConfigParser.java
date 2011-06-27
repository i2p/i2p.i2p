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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import net.i2p.util.SecureFile;
import net.i2p.util.SecureFileOutputStream;

/**
 * Utility class providing methods to parse and write files in config file
 * format, and subscription file format.
 * 
 * TODO: Change file encoding from default to UTF-8?
 *       Or switch to the DataHelper loadProps/storeProps methods?
 * 
 * @author Ragnarok
 */
class ConfigParser {

    private static final boolean isWindows = System.getProperty("os.name").startsWith("Win");

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
     * The key is converted to lower case.
     * 
     * @param input
     *            A BufferedReader with lines in key=value format to parse into
     *            a Map.
     * @return A Map containing the key, value pairs from input.
     * @throws IOException
     *             if the BufferedReader cannot be read.
     *  
     */
    public static Map<String, String>  parse(BufferedReader input) throws IOException {
        Map<String, String>  result = new HashMap();
        String inputLine;
        inputLine = input.readLine();
        while (inputLine != null) {
            inputLine = ConfigParser.stripComments(inputLine);
            String[] splitLine = inputLine.split("=");
            if (splitLine.length == 2) {
                result.put(splitLine[0].trim().toLowerCase(), splitLine[1].trim());
            }
            inputLine = input.readLine();
        }
        input.close();
        return result;
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
    public static Map<String, String>  parse(File file) throws IOException {
        FileInputStream fileStream = new FileInputStream(file);
        BufferedReader input = new BufferedReader(new InputStreamReader(
                fileStream));
        Map<String, String>  rv = ConfigParser.parse(input);
        try {
            fileStream.close();
        } catch (IOException ioe) {}
        return rv;
    }

    /**
     * Return a Map using the contents of the String string. See
     * parseBufferedReader for details of the input format.
     * 
     * @param string
     *            A String to parse.
     * @return A Map containing the key, value pairs from string.
     * @throws IOException
     *             if file cannot be read.
     */
    public static Map<String, String>  parse(String string) throws IOException {
        StringReader stringReader = new StringReader(string);
        BufferedReader input = new BufferedReader(stringReader);
        return ConfigParser.parse(input);
    }
    
    /**
     * Return a Map using the contents of the File file. If file cannot be read,
     * use map instead, and write the result to where file should have been.
     * 
     * @param file
     *            A File to attempt to parse.
     * @param map
     *            A Map containing values to use as defaults.
     * @return A Map containing the key, value pairs from file, or if file
     *         cannot be read, map.
     */
    public static Map<String, String>  parse(File file, Map<String, String>  map) {
        Map<String, String>  result;
        try {
            result = ConfigParser.parse(file);
            for (Map.Entry<String, String> entry : map.entrySet()) {
                if (!result.containsKey(entry.getKey()))
                    result.put(entry.getKey(), entry.getValue());
            }
        } catch (IOException exp) {
            result = map;
            try {
                ConfigParser.write(result, file);
            } catch (IOException exp2) {
            }
        }
        return result;
    }

    /**
     * Return a List where each element is a line from the BufferedReader input.
     * 
     * @param input
     *            A BufferedReader to parse.
     * @return A List consisting of one element for each line in input.
     * @throws IOException
     *             if input cannot be read.
     */
    public static List<String> parseSubscriptions(BufferedReader input)
            throws IOException {
        List<String> result = new LinkedList();
        String inputLine = input.readLine();
        while (inputLine != null) {
            inputLine = ConfigParser.stripComments(inputLine).trim();
            if (inputLine.length() > 0) {
                result.add(inputLine);
            }
            inputLine = input.readLine();
        }
        input.close();
        return result;
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
    public static List<String> parseSubscriptions(File file) throws IOException {
        FileInputStream fileStream = new FileInputStream(file);
        BufferedReader input = new BufferedReader(new InputStreamReader(
                fileStream));
        List<String> rv = ConfigParser.parseSubscriptions(input);
        try {
            fileStream.close();
        } catch (IOException ioe) {}
        return rv;
    }

    /**
     * Return a List where each element is a line from the String string.
     * 
     * @param string
     *            A String to parse.
     * @return A List consisting of one element for each line in string.
     * @throws IOException
     *             if string cannot be read.
     */
    public static List<String> parseSubscriptions(String string) throws IOException {
        StringReader stringReader = new StringReader(string);
        BufferedReader input = new BufferedReader(stringReader);
        return ConfigParser.parseSubscriptions(input);
    }
    
    /**
     * Return a List using the contents of the File file. If file cannot be
     * read, use list instead, and write the result to where file should have
     * been.
     * 
     * @param file
     *            A File to attempt to parse.
     * @param list list of files to parse
     * @return A List consisting of one element for each line in file, or if
     *         file cannot be read, list.
     */
    public static List<String> parseSubscriptions(File file, List<String> list) {
        List<String> result;
        try {
            result = ConfigParser.parseSubscriptions(file);
        } catch (IOException exp) {
            result = list;
            try {
                ConfigParser.writeSubscriptions(result, file);
            } catch (IOException exp2) {
            }
        }
        return result;
    }

    /**
     * Write contents of Map map to BufferedWriter output. Output is written
     * with one key, value pair on each line, in the format: key=value.
     * 
     * @param map
     *            A Map to write to output.
     * @param output
     *            A BufferedWriter to write the Map to.
     * @throws IOException
     *             if the BufferedWriter cannot be written to.
     */
    public static void write(Map<String, String>  map, BufferedWriter output) throws IOException {
        for (Map.Entry<String, String> entry : map.entrySet()) {
            output.write(entry.getKey() + '=' + entry.getValue());
            output.newLine();
        }
        output.close();
    }

    /**
     * Write contents of Map map to the File file. Output is written
     * with one key, value pair on each line, in the format: key=value.
     * Write to a temp file in the same directory and then rename, to not corrupt
     * simultaneous accesses by the router. Except on Windows where renameTo()
     * will fail if the target exists.
     *
     * @param map
     *            A Map to write to file.
     * @param file
     *            A File to write the Map to.
     * @throws IOException
     *             if file cannot be written to.
     */
    public static void write(Map<String, String>  map, File file) throws IOException {
        boolean success = false;
        if (!isWindows) {
            File tmp = SecureFile.createTempFile("temp-", ".tmp", file.getAbsoluteFile().getParentFile());
            ConfigParser
                .write(map, new BufferedWriter(new OutputStreamWriter(new SecureFileOutputStream(tmp), "UTF-8")));
            success = tmp.renameTo(file);
            if (!success) {
                tmp.delete();
                //System.out.println("Warning: addressbook rename fail from " + tmp + " to " + file);
            }
        }
        if (!success) {
            // hmm, that didn't work, try it the old way
            ConfigParser
                .write(map, new BufferedWriter(new OutputStreamWriter(new SecureFileOutputStream(file), "UTF-8")));
        }
    }

    /**
     * Write contents of List list to BufferedReader output. Output is written
     * with each element of list on a new line.
     * 
     * @param list
     *            A List to write to file.
     * @param output
     *            A BufferedReader to write list to.
     * @throws IOException
     *             if output cannot be written to.
     */
    public static void writeSubscriptions(List<String> list, BufferedWriter output)
            throws IOException {
        for (String s : list) {
            output.write(s);
            output.newLine();
        }
        output.close();
    }
    
    /**
     * Write contents of List list to File file. Output is written with each
     * element of list on a new line.
     * 
     * @param list
     *            A List to write to file.
     * @param file
     *            A File to write list to.
     * @throws IOException
     *             if output cannot be written to.
     */
    public static void writeSubscriptions(List<String> list, File file)
            throws IOException {
        ConfigParser.writeSubscriptions(list, new BufferedWriter(
                new OutputStreamWriter(new SecureFileOutputStream(file), "UTF-8")));
    }

}
