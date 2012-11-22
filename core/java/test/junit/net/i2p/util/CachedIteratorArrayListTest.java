package net.i2p.util;

import static org.junit.Assert.*;

import java.util.Iterator;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

public class CachedIteratorArrayListTest {

    private List<Integer> l;
    
    @Before
    public void setUp() {
        l = new CachedIteratorArrayList<Integer>();
        l.add(1);
        l.add(2);
        l.add(3);
    }
    
    @Test
    public void test() {
        // test for-each worx
        int total = 0;
        for (int i : l)
            total += i;
        assertEquals(6, total);
        for (int i : l)
            total += i;
        assertEquals(12, total);
        
    }
    
    @Test
    public void testSameness() {
        Iterator<Integer> two = l.iterator();
        Iterator<Integer> one = l.iterator();
        assertSame(one, two);
    }

}
