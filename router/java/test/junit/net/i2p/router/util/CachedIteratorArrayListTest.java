package net.i2p.router.util;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

public class CachedIteratorArrayListTest {

    private List<Character> l;
    private Iterator<Character> iter; 
    
    @Before
    public void setUp() {
        l = new CachedIteratorArrayList<Character>();
        l.add('a');
        l.add('b');
        l.add('c');
        iter = l.iterator();
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
        iter = l.iterator();
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
    
    @Test
    public void testRemove() {
        iter.next();
        iter.remove();

        // test proper removal
        assertEquals(2,l.size());
        assertEquals('b',l.get(0).charValue());
        assertEquals('c',l.get(1).charValue());
        
        // test iterator still workx after removal
        assertTrue(iter.hasNext());
        assertEquals('b',iter.next().charValue());
        assertEquals('c',iter.next().charValue());
        assertFalse(iter.hasNext());
    }
    
    /** 
     * tests the Collections.sort method because that is used
     * in the router and internally creates iterators
     */
    @Test
    public void testSorting() {
        List<Integer> li = new CachedIteratorArrayList<Integer>();
        li.add(3);
        li.add(2);
        li.add(1);
        Collections.sort(li);
        
        Iterator<Integer> ii = li.iterator();
        assertEquals(1,ii.next().intValue());
        assertEquals(2,ii.next().intValue());
        assertEquals(3,ii.next().intValue());
        
        assertFalse(ii.hasNext());
    }
}
