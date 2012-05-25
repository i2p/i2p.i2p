package com.nettgryppa.security;
// Copyright 2006 Gregory Rubin grrubin@gmail.com
//  Permission is given to use, modify, and or distribute this code so long as this message remains attached
//  Please see the spec at: http://www.hashcash.org/

import java.util.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.security.NoSuchAlgorithmException; 

/**
 * Class for generation and parsing of <a href="http://www.hashcash.org/">HashCash</a><br>
 * Copyright 2006 Gregory Rubin <a href="mailto:grrubin@gmail.com">grrubin@gmail.com</a><br>
 *  Permission is given to use, modify, and or distribute this code so long as this message remains attached<br>
 * Please see the spec at: <a href="http://www.hashcash.org/">http://www.hashcash.org/</a>
 * @author grrubin@gmail.com
 * @version 1.1
 */
public class HashCash implements Comparable<HashCash> {
  public static final int DefaultVersion = 1;
  private static final int hashLength = 160;
  private static final String dateFormatString = "yyMMdd";
  private static long milliFor16 = -1;
  
  private String myToken;
  private int myValue;
  private Calendar myDate;
  private Map<String, List<String> > myExtensions;
  private int myVersion;
  private String myResource;
  // Constructors
  
  /**
      * Parses and validates a HashCash.
      * @throws NoSuchAlgorithmException If SHA1 is not a supported Message Digest
      */
  public HashCash(String cash) throws NoSuchAlgorithmException {
    myToken = cash;
    String[] parts = cash.split(":");
    myVersion = Integer.parseInt(parts[0]);
    if(myVersion < 0 || myVersion > 1)
      throw new IllegalArgumentException("Only supported versions are 0 and 1");
      
    if((myVersion == 0 && parts.length != 6) ||
       (myVersion == 1 && parts.length != 7))
      throw new IllegalArgumentException("Improperly formed HashCash");

  	try {
  	    int index = 1;
  	    if(myVersion == 1)
  	      myValue = Integer.parseInt(parts[index++]);
  	    else
  	      myValue = 0;
  	    
  	    SimpleDateFormat dateFormat = new SimpleDateFormat(dateFormatString);
  	    Calendar tempCal = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
  	    tempCal.setTime(dateFormat.parse(parts[index++]));
  	    
  	    myResource = parts[index++];
  	    myExtensions = deserializeExtensions(parts[index++]);
  	    
  	    MessageDigest md = MessageDigest.getInstance("SHA1");
  	    md.update(cash.getBytes());
  	    byte[] tempBytes = md.digest();
  	    int tempValue = numberOfLeadingZeros(tempBytes);
  	    
  	    if(myVersion == 0)
  	      myValue = tempValue;
  	    else if (myVersion == 1)
  	      myValue = (tempValue > myValue ? myValue : tempValue);
  	} catch (java.text.ParseException ex) {
  		throw new IllegalArgumentException("Improperly formed HashCash", ex);
  	}
  }
  
  private HashCash() throws NoSuchAlgorithmException {
  }
  
  /**
       * Mints a version 1 HashCash using now as the date
       * @param resource the string to be encoded in the HashCash
       * @throws NoSuchAlgorithmException If SHA1 is not a supported Message Digest
       */
  public static HashCash mintCash(String resource, int value) throws NoSuchAlgorithmException {
    Calendar now = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
    return mintCash(resource, null, now, value, DefaultVersion);
  }
  
  /**
       * Mints a  HashCash  using now as the date
       * @param resource the string to be encoded in the HashCash
       * @param version Which version to mint.  Only valid values are 0 and 1
       * @throws NoSuchAlgorithmException If SHA1 is not a supported Message Digest
       */
  public static HashCash mintCash(String resource, int value, int version) throws NoSuchAlgorithmException {
    Calendar now = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
    return mintCash(resource, null, now, value, version);
  }
  
    /**
       * Mints a version 1 HashCash
       * @param resource the string to be encoded in the HashCash
       * @throws NoSuchAlgorithmException If SHA1 is not a supported Message Digest
       */
  public static HashCash mintCash(String resource, Calendar date, int value) throws NoSuchAlgorithmException {
    return mintCash(resource, null, date, value, DefaultVersion);
  }
    
  /**
       * Mints a  HashCash
       * @param resource the string to be encoded in the HashCash
       * @param version Which version to mint.  Only valid values are 0 and 1
       * @throws NoSuchAlgorithmException If SHA1 is not a supported Message Digest
       */
  public static HashCash mintCash(String resource, Calendar date, int value, int version) 
  throws NoSuchAlgorithmException {
    return mintCash(resource, null, date, value, version);
  }
  
  /**
       * Mints a version 1 HashCash using now as the date
       * @param resource the string to be encoded in the HashCash
       * @param extensions Extra data to be encoded in the HashCash
       * @throws NoSuchAlgorithmException If SHA1 is not a supported Message Digest
       */
  public static HashCash mintCash(String resource, Map<String, List<String> > extensions, int value) 
  throws NoSuchAlgorithmException {
    Calendar now = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
    return mintCash(resource, extensions, now, value, DefaultVersion);
  }
  
  /**
       * Mints a  HashCash using now as the date
       * @param resource the string to be encoded in the HashCash
       * @param extensions Extra data to be encoded in the HashCash
       * @param version Which version to mint.  Only valid values are 0 and 1
       * @throws NoSuchAlgorithmException If SHA1 is not a supported Message Digest
       */
  public static HashCash mintCash(String resource, Map<String, List<String> > extensions, int value, int version)
  throws NoSuchAlgorithmException {
    Calendar now = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
    return mintCash(resource, extensions, now, value, version);
  }
  
  /**
       * Mints a version 1 HashCash
       * @param resource the string to be encoded in the HashCash
       * @param extensions Extra data to be encoded in the HashCash
       * @throws NoSuchAlgorithmException If SHA1 is not a supported Message Digest
       */
  public static HashCash mintCash(String resource, Map<String, List<String> > extensions, Calendar date, int value) 
  throws NoSuchAlgorithmException {
    return mintCash(resource, extensions, date, value, DefaultVersion);
  }
  
  /**
       * Mints a  HashCash
       * @param resource the string to be encoded in the HashCash
       * @param extensions Extra data to be encoded in the HashCash
       * @param version Which version to mint.  Only valid values are 0 and 1
       * @throws NoSuchAlgorithmException If SHA1 is not a supported Message Digest
       */
  public static HashCash mintCash(String resource, Map<String, List<String> > extensions, Calendar date, int value, int version) 
  throws NoSuchAlgorithmException {
    if(version < 0 || version > 1)
      throw new IllegalArgumentException("Only supported versions are 0 and 1");
      
    if(value < 0 || value > hashLength)
      throw new IllegalArgumentException("Value must be between 0 and " + hashLength);

    if(resource.contains(":"))
        throw new IllegalArgumentException("Resource may not contain a colon.");
    
	HashCash result = new HashCash();
	  
    MessageDigest md = MessageDigest.getInstance("SHA1");
    
    result.myResource = resource;
    result.myExtensions = (null == extensions ? new HashMap<String, List<String> >() : extensions);
    result.myDate = date;
    result.myVersion = version;
    
    String prefix;
    
    SimpleDateFormat dateFormat = new SimpleDateFormat(dateFormatString);
    switch(version) {
      case 0:
        prefix = version + ":" + dateFormat.format(date.getTime()) + ":" + resource + ":" + 
        serializeExtensions(extensions) + ":";
        result.myToken = generateCash(prefix, value, md);
        md.reset();
        md.update(result.myToken.getBytes());
        result.myValue = numberOfLeadingZeros(md.digest());
        break;
      
      case 1:
        result.myValue = value;
        prefix = version + ":" + value + ":" + dateFormat.format(date.getTime()) + ":" + resource + ":" + 
        serializeExtensions(extensions) + ":";
        result.myToken = generateCash(prefix, value, md);
        break;
        
      default:
        throw new IllegalArgumentException("Only supported versions are 0 and 1");
    }
	
	return result;
  }
  
  // Accessors
  /**
       * Two objects are considered equal if they are both of type HashCash and have an identical string representation
       */
  public boolean equals(Object obj) {
    if(obj instanceof HashCash)
      return toString().equals(obj.toString());
    else
      return super.equals(obj);
  }
  
  /**
       * Returns the canonical string representation of the HashCash
       */
  public String toString() {
    return myToken;
  }
  
  /**
       * Extra data encoded in the HashCash
       */
  public Map<String, List<String> > getExtensions() {
    return myExtensions;
  }
  
  /**
       * The primary resource being protected
       */
  public String getResource() {
    return myResource;
  }
  
  /**
       * The minting date
       */
  public Calendar getDate() {
    return myDate;
  }
  
  /**
       * The value of the HashCash (e.g. how many leading zero bits it has)
       */
  public int getValue() {
    return myValue;
  }
  
  /**
       * Which version of HashCash is used here
       */
  public int getVersion() {
    return myVersion;
  }
  
  // Private utility functions
  /**
       * Actually tries various combinations to find a valid hash.  Form is of prefix + random_hex + ":" + random_hex
       * @throws NoSuchAlgorithmException If SHA1 is not a supported Message Digest
       */
  private static String generateCash(String prefix, int value, MessageDigest md) 
  throws NoSuchAlgorithmException {
    SecureRandom rnd = SecureRandom.getInstance("SHA1PRNG");
    byte[] tmpBytes = new byte[8];
    rnd.nextBytes(tmpBytes);
    long random = bytesToLong(tmpBytes);
    rnd.nextBytes(tmpBytes);
    long counter = bytesToLong(tmpBytes);
    
    prefix = prefix + Long.toHexString(random) + ":";
    
    String temp;
    int tempValue;
    byte[] bArray;
    do {
      counter++;
      temp = prefix + Long.toHexString(counter);
      md.reset();
      md.update(temp.getBytes());
      bArray = md.digest();
      tempValue = numberOfLeadingZeros(bArray);
    } while ( tempValue < value);
    
    return temp;
  }
  
  /**
   * Converts a 8 byte array of unsigned bytes to an long
   * @param b an array of 8 unsigned bytes
   */
private static long bytesToLong(byte[] b) {
    long l = 0;
    l |= b[0] & 0xFF;
    l <<= 8;
    l |= b[1] & 0xFF;
    l <<= 8;
    l |= b[2] & 0xFF;
    l <<= 8;
    l |= b[3] & 0xFF;
	l <<= 8;
    l |= b[4] & 0xFF;
    l <<= 8;
    l |= b[5] & 0xFF;
    l <<= 8;
    l |= b[6] & 0xFF;
	l <<= 8;
    l |= b[7] & 0xFF;
    return l;
  }
  
  /**
   * Serializes the extensions with (key, value) seperated by semi-colons and values seperated by commas
   */
  private static String serializeExtensions(Map<String, List<String> > extensions) {
    if(null == extensions || extensions.isEmpty())
      return "";
      
    StringBuilder result = new StringBuilder();
    List<String> tempList;
    boolean first = true;
    
    for(String key: extensions.keySet()) {
      if(key.contains(":") || key.contains(";") || key.contains("="))
          throw new IllegalArgumentException("Extension key contains an illegal character. " + key);
      if(!first)
        result.append(";");
      first = false;
      result.append(key);
      tempList = extensions.get(key);
      
      if(null != tempList) {
        result.append("=");
        for(int i = 0; i < tempList.size(); i++) {
          if(tempList.get(i).contains(":") || tempList.get(i).contains(";") || tempList.get(i).contains(","))
              throw new IllegalArgumentException("Extension value contains an illegal character. " + tempList.get(i));
          if(i > 0)
            result.append(",");
          result.append(tempList.get(i));
        }
      }
    }
    return result.toString();
  }
  
  /**
       * Inverse of {@link #serializeExtensions(Map)}
       */
  private static Map<String, List<String> > deserializeExtensions(String extensions) {
    Map<String, List<String> > result = new HashMap<String, List<String> >();
    if(null == extensions || extensions.length() == 0)
        return result;
    
    String[] items = extensions.split(";");
    
    for(int i = 0; i < items.length; i++) {
      String[] parts = items[i].split("=", 2);
      if(parts.length == 1)
        result.put(parts[0], null);
      else
        result.put(parts[0], Arrays.asList(parts[1].split(",")));
    }
    
    return result;
  }
  
  /**
       * Counts the number of leading zeros in a byte array.
       */
  private static int numberOfLeadingZeros(byte[] values) {
    int result = 0;
    int temp = 0;
    for(int i = 0; i < values.length; i++) {
      
      temp = numberOfLeadingZeros(values[i]);
      
      result += temp;
      if(temp != 8)
        break;
    }
    
    return result;
  }
  
  /**
   * Returns the number of leading zeros in a bytes binary represenation
   */
  private static int numberOfLeadingZeros(byte value) {
    if(value < 0)
      return 0;
    if(value < 1)
      return 8;
    else if (value < 2)
      return  7;
    else if (value < 4)
      return 6;
    else if (value < 8)
      return 5;
    else if (value < 16)
      return 4;
    else if (value < 32)
      return 3;
    else if (value < 64)
      return 2;
    else if (value < 128)
      return 1;
    else
      return 0;
  }

  /**
       * Estimates how many milliseconds it would take to mint a cash of the specified value.
       * <ul>
       * <li>NOTE1: Minting time can vary greatly in fact, half of the time it will take half as long)
       * <li>NOTE2: The first time that an estimation function is called it is expensive (on the order of seconds).  After that, it is very quick.
       * </ul>
       * @throws NoSuchAlgorithmException If SHA1 is not a supported Message Digest
       */
  public static long estimateTime(int value) throws NoSuchAlgorithmException {
    initEstimates();
    return (long)(milliFor16 * Math.pow(2, value - 16));
  }

  /**
       * Estimates what value (e.g. how many bits of collision) are required for the specified  length of time. 
       * <ul>
       * <li>NOTE1: Minting time can vary greatly in fact, half of the time it will take half as long)
       * <li>NOTE2: The first time that an estimation function is called it is expensive (on the order of seconds).  After that, it is very quick.
       * </ul>
       * @throws NoSuchAlgorithmException If SHA1 is not a supported Message Digest
       */
  public static int estimateValue(int secs) throws NoSuchAlgorithmException  {
    initEstimates();
    int result = 0;
    long millis = secs * 1000 * 65536;
    millis /= milliFor16;
    
    while(millis > 1) {
      result++;
      millis /= 2;
    }
    
    return result;
  }
  
  /**
       * Seeds the estimates by determining how long it takes to calculate a 16bit collision on average.
       * @throws NoSuchAlgorithmException If SHA1 is not a supported Message Digest
       */
  private static void initEstimates() throws NoSuchAlgorithmException {
    if(milliFor16 == -1) {
      long duration;
      duration = Calendar.getInstance().getTimeInMillis();
      for(int i = 0; i < 11; i++) {
        mintCash("estimation", 16);
      }
      duration = Calendar.getInstance().getTimeInMillis() - duration;
      milliFor16 = (duration /10);
    }
  }
  
  /**
   * Compares the value of two HashCashes 
   * @param other
   * @see java.lang.Comparable#compareTo(Object)
   */
  public int compareTo(HashCash other) {
      if(null == other)
          throw new NullPointerException();
      
      return Integer.valueOf(getValue()).compareTo(Integer.valueOf(other.getValue()));
  }
}