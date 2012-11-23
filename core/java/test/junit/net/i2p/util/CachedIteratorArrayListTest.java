package net.i2p.util;

import static org.junit.Assert.*;

import java.util.Iterator;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

public class CachedIteratorArrayListTest {

    private List<Character> l;
    
    @Before
    public void setUp() {
        l = new CachedIteratorArrayList<Character>();
        l.add('a');
        l.add('b');
        l.add('c');
    }
    
    /** test iterations work */
    @Test
    public void test() {
        String total = "";
        
        // two full for-each iterations
        for (char i : l)
            total += i;
        assertEquals("abc", total);
        for (char i : l)
            total += i;
        assertEquals("abcabc", total);
        
        // and one partial
        total = "";
        Iterator<Character> iter = l.iterator();
        total += iter.next();
        total += iter.next();
        iter = l.iterator();
        total += iter.next();
        assertEquals("aba",total);
    }
    
    @Test
    public void testSameness() {
        Iterator<Character> two = l.iterator();
        Iterator<Character> one = l.iterator();
        assertSame(one, two);
    }

}
