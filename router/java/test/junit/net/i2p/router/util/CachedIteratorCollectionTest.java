package net.i2p.router.util;

import static org.junit.Assert.*;
import org.junit.Test;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.ArrayList;
import java.util.NoSuchElementException;

/* when using with JUnit5+Idea
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.fail;
*/



/**
 * A set of tests to ensure that the CachedIteratorCollection class is
 * functioning as intended
 */

public class CachedIteratorCollectionTest {

    /* Add n-number of objects to the given CachedIteratorCollection object
     *
     */
    private void AddNObjects(CachedIteratorCollection<String> a, int n) {
        if (n > 0) {
            for (int j = 0; j < n; j++) {
                String s = "test" + j;
                a.add(s);
            }
        } else {
            throw new UnsupportedOperationException("Please use a positive integer");
        }
    }

    private void AddNObjectsLL(LinkedList<String> a, int n) {
        if (n > 0) {
            for (int j = 0; j < n; j++) {
                String s = "test" + j;
                a.add(s);
            }
        } else {
            throw new UnsupportedOperationException("Please use a positive integer");
        }
    }

    //@DisplayName("Add 1 Element Test")
    @Test
    public void Add1Test() {
        CachedIteratorCollection<String> testCollection1 = new CachedIteratorCollection<>();

        AddNObjects(testCollection1, 1);
        String testString = "";

        // Iterator test
        Iterator<String> testCollection1Itr = testCollection1.iterator();

        while(testCollection1Itr.hasNext()) {
            testString += testCollection1Itr.next();
        }

        assertEquals("test0", testString);
        assertEquals(1, testCollection1.size());
        assertFalse(testCollection1Itr.hasNext());
        assertFalse(testCollection1.isEmpty());

        // Compare behavior with LinkedList object
        LinkedList<String> testCollection2 = new LinkedList<>();

        AddNObjectsLL(testCollection2, 1);
        testString = "";

        // Iterator test
        Iterator<String> testCollection2Itr = testCollection2.iterator();

        while(testCollection2Itr.hasNext()) {
            testString += testCollection2Itr.next();
        }

        assertEquals("test0", testString);
        assertEquals(1, testCollection2.size());
        assertFalse(testCollection2Itr.hasNext());
        assertFalse(testCollection2.isEmpty());
    }

    //@DisplayName("Add 10 Elements Test")
    @Test
    public void Add10Test() {
        CachedIteratorCollection<String> testCollection1 = new CachedIteratorCollection<>();

        AddNObjects(testCollection1, 10);
        String testString = "";

        // Iterator test
        Iterator<String> testCollection1Itr = testCollection1.iterator();

        while(testCollection1Itr.hasNext()) {
            testString += testCollection1Itr.next();
        }

        assertEquals("test0test1test2test3test4test5test6test7test8test9", testString);
        assertEquals(10, testCollection1.size());
        assertFalse(testCollection1Itr.hasNext());
        assertFalse(testCollection1.isEmpty());

        // Compare behavior with LinkedList OBject
        LinkedList<String> testCollection2 = new LinkedList<>();

        AddNObjectsLL(testCollection2, 10);
        testString = "";

        // Iterator test
        Iterator<String> testCollection2Itr = testCollection2.iterator();

        while(testCollection2Itr.hasNext()) {
            testString += testCollection2Itr.next();
        }

        assertEquals("test0test1test2test3test4test5test6test7test8test9", testString);
        assertEquals(10, testCollection2.size());
        assertFalse(testCollection2Itr.hasNext());
        assertFalse(testCollection2.isEmpty());
    }

    //@DisplayName("AddAll() Test")
    @Test
    public void AddAll() {
        CachedIteratorCollection<String> testCollection1 = new CachedIteratorCollection<>();

        ArrayList<String> stringArrayList = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            String s = "test" + i;
            stringArrayList.add(s);
        }

        testCollection1.addAll(stringArrayList);

        // Iterator test
        Iterator<String> testCollection1Itr = testCollection1.iterator();

        String testString = "";

        while(testCollection1Itr.hasNext()) {
            testString += testCollection1Itr.next();
        }

        assertEquals("test0test1test2test3test4test5test6test7test8test9", testString);
        assertEquals(10, testCollection1.size());
        assertFalse(testCollection1Itr.hasNext());
        assertFalse(testCollection1.isEmpty());

        // Compare behavior with LinkedList object
        LinkedList<String> testCollection2 = new LinkedList<>();

        stringArrayList = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            String s = "test" + i;
            stringArrayList.add(s);
        }

        testCollection2.addAll(stringArrayList);

        // Iterator test
        Iterator<String> testCollection2Itr = testCollection2.iterator();

        testString = "";

        while(testCollection2Itr.hasNext()) {
            testString += testCollection2Itr.next();
        }

        assertEquals("test0test1test2test3test4test5test6test7test8test9", testString);
        assertEquals(10, testCollection2.size());
        assertFalse(testCollection2Itr.hasNext());
        assertFalse(testCollection2.isEmpty());
    }

    //@DisplayName("Single Element Remove Test - Size 1 Collection")
    @Test
    public void SingleRemove() {
        CachedIteratorCollection<String> testCollection1 = new CachedIteratorCollection<>();

        AddNObjects(testCollection1, 1);
        String testString = "";

        // Iterator test
        Iterator<String> testCollection1Itr = testCollection1.iterator();

        while(testCollection1Itr.hasNext()) {
            testString += testCollection1Itr.next();
            testCollection1Itr.remove();
        }

        assertEquals("test0", testString);
        assertEquals(0, testCollection1.size());
        assertFalse(testCollection1Itr.hasNext());
        assertTrue(testCollection1.isEmpty());

        // Compare behavior with LinkedList object
        LinkedList<String> testCollection2 = new LinkedList<>();

        AddNObjectsLL(testCollection2, 1);
        testString = "";

        // Iterator test
        Iterator<String> testCollection2Itr = testCollection2.iterator();

        while(testCollection2Itr.hasNext()) {
            testString += testCollection2Itr.next();
            testCollection2Itr.remove();
        }

        assertEquals("test0", testString);
        assertEquals(0, testCollection2.size());
        assertFalse(testCollection2Itr.hasNext());
        assertTrue(testCollection2.isEmpty());
    }

    //@DisplayName("Single Element Remove From Start Test - Size 10 Collection")
    @Test
    public void RemoveFromStart() {
        CachedIteratorCollection<String> testCollection1 = new CachedIteratorCollection<>();

        AddNObjects(testCollection1, 10);
        String testString = "";

        // Iterator test
        Iterator<String> testCollection1Itr = testCollection1.iterator();

        String s = "";
        while (testCollection1Itr.hasNext()) {
            s = testCollection1Itr.next();
            if (s.equals("test0")) {
                testCollection1Itr.remove();
            } else {
                testString += s;
            }
        }

        assertEquals("test1test2test3test4test5test6test7test8test9", testString);
        assertEquals(9, testCollection1.size());
        assertFalse(testCollection1Itr.hasNext());
        assertFalse(testCollection1.isEmpty());

        // Compare behavior with LinkedList object
        LinkedList<String> testCollection2 = new LinkedList<>();

        AddNObjectsLL(testCollection2, 10);
        testString = "";

        // Iterator test
        Iterator<String> testCollection2Itr = testCollection2.iterator();

        s = "";
        while (testCollection2Itr.hasNext()) {
            s = testCollection2Itr.next();
            if (s.equals("test0")) {
                testCollection2Itr.remove();
            } else {
                testString += s;
            }
        }

        assertEquals("test1test2test3test4test5test6test7test8test9", testString);
        assertEquals(9, testCollection2.size());
        assertFalse(testCollection2Itr.hasNext());
        assertFalse(testCollection2.isEmpty());
    }

    //@DisplayName("Single Element Remove From Middle Test")
    @Test
    public void RemoveFromMiddle() {
        CachedIteratorCollection<String> testCollection1 = new CachedIteratorCollection<>();

        AddNObjects(testCollection1, 10);
        String testString = "";

        // Iterator test
        Iterator<String> testCollection1Itr = testCollection1.iterator();

        String s = "";
        while (testCollection1Itr.hasNext()) {
            s = testCollection1Itr.next();
            if (s.equals("test5")) {
                testCollection1Itr.remove();
            } else {
                testString += s;
            }
        }

        assertEquals("test0test1test2test3test4test6test7test8test9", testString);
        assertEquals(9, testCollection1.size());
        assertFalse(testCollection1Itr.hasNext());
        assertFalse(testCollection1.isEmpty());

        // Compare behavior with LinkedList object
        LinkedList<String> testCollection2 = new LinkedList<>();

        AddNObjectsLL(testCollection2, 10);
        testString = "";

        // Iterator test
        Iterator<String> testCollection2Itr = testCollection2.iterator();

        s = "";
        while (testCollection2Itr.hasNext()) {
            s = testCollection2Itr.next();
            if (s.equals("test5")) {
                testCollection2Itr.remove();
            } else {
                testString += s;
            }
        }

        assertEquals("test0test1test2test3test4test6test7test8test9", testString);
        assertEquals(9, testCollection2.size());
        assertFalse(testCollection2Itr.hasNext());
        assertFalse(testCollection2.isEmpty());
    }

    //@DisplayName("Multiple Elements Remove From Middle Test")
    @Test
    public void RemoveMultipleFromMiddle() {
        CachedIteratorCollection<String> testCollection1 = new CachedIteratorCollection<>();

        AddNObjects(testCollection1, 10);
        String testString = "";

        // Iterator test
        Iterator<String> testCollection1Itr = testCollection1.iterator();

        String s = "";
        while (testCollection1Itr.hasNext()) {
            s = testCollection1Itr.next();
            if (s.equals("test5") || s.equals("test6")) {
                testCollection1Itr.remove();
            } else {
                testString += s;
            }
        }

        assertEquals("test0test1test2test3test4test7test8test9", testString);
        assertEquals(8, testCollection1.size());
        assertFalse(testCollection1Itr.hasNext());
        assertFalse(testCollection1.isEmpty());

        // Compare behavior with LinkedList object
        LinkedList<String> testCollection2 = new LinkedList<>();

        AddNObjectsLL(testCollection2, 10);
        testString = "";

        // Iterator test
        Iterator<String> testCollection2Itr = testCollection2.iterator();

        s = "";
        while (testCollection2Itr.hasNext()) {
            s = testCollection2Itr.next();
            if (s.equals("test5") || s.equals("test6")) {
                testCollection2Itr.remove();
            } else {
                testString += s;
            }
        }

        assertEquals(8, testCollection2.size());
        assertEquals("test0test1test2test3test4test7test8test9", testString);
        assertFalse(testCollection2Itr.hasNext());
        assertFalse(testCollection2.isEmpty());
    }

    //@DisplayName("Single Element Remove From End Test")
    @Test
    public void RemoveFromEnd() {
        CachedIteratorCollection<String> testCollection1 = new CachedIteratorCollection<>();

        AddNObjects(testCollection1, 10);
        String testString = "";

        // Iterator test
        Iterator<String> testCollection1Itr = testCollection1.iterator();

        String s = "";
        while (testCollection1Itr.hasNext()) {
            s = testCollection1Itr.next();
            if (s.equals("test9")) {
                testCollection1Itr.remove();
            } else {
                testString += s;
            }
        }

        assertEquals("test0test1test2test3test4test5test6test7test8", testString);
        assertEquals(9, testCollection1.size());
        assertFalse(testCollection1Itr.hasNext());
        assertFalse(testCollection1.isEmpty());

        // Compare behavior with LinkedList object
        LinkedList<String> testCollection2 = new LinkedList<>();

        AddNObjectsLL(testCollection2, 10);
        testString = "";

        // Iterator test
        Iterator<String> testCollection2Itr = testCollection2.iterator();

        s = "";
        while (testCollection2Itr.hasNext()) {
            s = testCollection2Itr.next();
            if (s.equals("test9")) {
                testCollection2Itr.remove();
            } else {
                testString += s;
            }
        }

        assertEquals(9, testCollection2.size());
        assertEquals("test0test1test2test3test4test5test6test7test8", testString);
        assertFalse(testCollection2Itr.hasNext());
        assertFalse(testCollection2.isEmpty());
    }

    //@DisplayName("Remove All - Size 10 Collection")
    @Test
    public void RemoveAll() {
        CachedIteratorCollection<String> testCollection1 = new CachedIteratorCollection<>();

        AddNObjects(testCollection1, 10);
        String testString = "";

        // Iterator test
        Iterator<String> testCollection1Itr = testCollection1.iterator();

        assertEquals(10, testCollection1.size());
        assertTrue(testCollection1Itr.hasNext());
        assertFalse(testCollection1.isEmpty());

        // Remove all test
        while (testCollection1Itr.hasNext()) {
            testCollection1Itr.next();
            testCollection1Itr.remove();
        }

        assertEquals(0, testCollection1.size());
        assertFalse(testCollection1Itr.hasNext());
        assertTrue(testCollection1.isEmpty());

        // Compare behavior with LinkedList object
        LinkedList<String> testCollection2 = new LinkedList<>();

        AddNObjectsLL(testCollection2, 10);

        // Iterator test
        Iterator<String> testCollection2Itr = testCollection2.iterator();

        assertEquals(10, testCollection2.size());
        assertTrue(testCollection2Itr.hasNext());
        assertFalse(testCollection2.isEmpty());

        // Remove all test
        while (testCollection2Itr.hasNext()) {
            testCollection2Itr.next();
            testCollection2Itr.remove();
        }

        assertEquals(0, testCollection2.size());
        assertFalse(testCollection2Itr.hasNext());
        assertTrue(testCollection2.isEmpty());
    }

    //@DisplayName("Test Iterator Equality/Inequality")
    @Test
    public void Equality() {
        CachedIteratorCollection<String> testCollection1 = new CachedIteratorCollection<>();

        AddNObjects(testCollection1, 10);
        Iterator<String> testCollection1Itr1 = testCollection1.iterator();
        Iterator<String> testCollection1Itr2 = testCollection1.iterator();
        Iterator<String> testCollection1Itr3 = testCollection1.iterator();
        Iterator<String> testCollection1Itr4 = testCollection1.iterator();

        assertSame(testCollection1Itr1,testCollection1Itr2);
        assertSame(testCollection1Itr2,testCollection1Itr3);
        assertSame(testCollection1Itr3,testCollection1Itr4);

        // LinkedList object's iterator should return different objects
        LinkedList<String> testCollection2 = new LinkedList<>();

        AddNObjectsLL(testCollection2, 10);
        Iterator<String> testCollection2Itr1 = testCollection2.iterator();
        Iterator<String> testCollection2Itr2 = testCollection2.iterator();
        Iterator<String> testCollection2Itr3 = testCollection2.iterator();
        Iterator<String> testCollection2Itr4 = testCollection2.iterator();

        assertNotSame(testCollection2Itr1,testCollection2Itr2);
        assertNotSame(testCollection2Itr2,testCollection2Itr3);
        assertNotSame(testCollection2Itr3,testCollection2Itr4);
    }

    //@DisplayName("Restart Iterator Test")
    @Test
    public void Restart() {
        CachedIteratorCollection<String> testCollection1 = new CachedIteratorCollection<>();

        AddNObjects(testCollection1, 10);

        String testString = "";

        // Iterator test
        Iterator<String> testCollection1Itr = testCollection1.iterator();

        String s = "";
        while(testCollection1Itr.hasNext()) {
            testString += testCollection1Itr.next();
        }

        assertEquals("test0test1test2test3test4test5test6test7test8test9", testString);
        assertEquals(10, testCollection1.size());
        assertFalse(testCollection1Itr.hasNext());
        assertFalse(testCollection1.isEmpty());

        // Restart Iterator
        testCollection1Itr = testCollection1.iterator();

        assertTrue("test0".equals(testCollection1Itr.next()));
        assertEquals(10, testCollection1.size());
        assertTrue(testCollection1Itr.hasNext());
        assertFalse(testCollection1.isEmpty());

        // Compare behavior with LinkedList object
        LinkedList<String> testCollection2 = new LinkedList<>();

        AddNObjectsLL(testCollection2, 10);

        testString = "";

        // Iterator test
        Iterator<String> testCollection2Itr = testCollection2.iterator();

        while(testCollection2Itr.hasNext()) {
            testString += testCollection2Itr.next();
        }

        assertEquals("test0test1test2test3test4test5test6test7test8test9", testString);
        assertEquals(10, testCollection2.size());
        assertFalse(testCollection2Itr.hasNext());
        assertFalse(testCollection2.isEmpty());

        // Restart Iterator
        testCollection2Itr = testCollection2.iterator();

        assertEquals(10, testCollection2.size());
        assertTrue("test0".equals(testCollection2Itr.next()));
        assertTrue(testCollection2Itr.hasNext());
        assertFalse(testCollection2.isEmpty());

    }

    //@DisplayName("Clear Test")
    @Test
    public void Clear() {
        CachedIteratorCollection<String> testCollection1 = new CachedIteratorCollection<>();

        AddNObjects(testCollection1, 10);

        String testString = "";

        // Iterator test
        Iterator<String> testCollection1Itr = testCollection1.iterator();

        assertEquals(10, testCollection1.size());
        assertTrue(testCollection1Itr.hasNext());
        assertFalse(testCollection1.isEmpty());

        // Clear
        testCollection1.clear();

        assertEquals(0, testCollection1.size());
        assertFalse(testCollection1Itr.hasNext());
        assertTrue(testCollection1.isEmpty());

        // Compare behavior with LinkedList object
        LinkedList<String> testCollection2 = new LinkedList<>();

        AddNObjectsLL(testCollection2, 10);

        testString = "";

        // Iterator test
        Iterator<String> testCollection2Itr = testCollection2.iterator();

        assertEquals(10, testCollection2.size());
        assertFalse(testCollection2.isEmpty());
        assertTrue(testCollection2Itr.hasNext());

        // Clear
        testCollection2.clear();

        assertEquals(0, testCollection2.size());
        assertTrue(testCollection2.isEmpty());
        assertFalse(testCollection2Itr.hasNext());
    }

    //@DisplayName("Remove Twice Before next() Test")
    @Test
    public void RemoveTwice() {
        CachedIteratorCollection<String> testCollection1 = new CachedIteratorCollection<>();

        AddNObjects(testCollection1, 10);

        Iterator<String> testCollection1Itr = testCollection1.iterator();
        testCollection1Itr.next();
        testCollection1Itr.remove();
        testCollection1Itr.next();
        testCollection1Itr.remove();

        /* Junit5 + java8+
        assertThrows(IllegalStateException.class, () -> {
            // Remove called once more here before a next()
            testCollection1Itr.remove();
        });
        */

        try {
            testCollection1Itr.remove();
            fail("Exception should be caught; This should not print.");
        } catch (IllegalStateException e) {
            System.out.println("RemoveTwice()+CachedIteratorCollection: IllegalStateException caught successfully." + e);
        }

        // Compare behavior with LinkedList object
        LinkedList<String> testCollection2 = new LinkedList<>();

        AddNObjectsLL(testCollection2, 10);

        Iterator<String> testCollection2Itr = testCollection2.iterator();
        testCollection2Itr.next();
        testCollection2Itr.remove();
        testCollection2Itr.next();
        testCollection2Itr.remove();

        /* Junit5 + java8+
        assertThrows(IllegalStateException.class, () -> {
            // Remove called once more here before a next()
            testCollection2Itr.remove();
        });
        */

        try {
            testCollection2Itr.remove();
            fail("Exception should be caught; This should not print.");
        } catch (IllegalStateException e) {
            System.out.println("RemoveTwice()+LinkedList: IllegalStateException caught successfully." + e);
        }
    }

    //@DisplayName("next() When No Elements Left Test")
    @Test
    public void NextWhenNone() {
        CachedIteratorCollection<String> testCollection1 = new CachedIteratorCollection<>();

        AddNObjects(testCollection1, 10);

        Iterator<String> testCollection1Itr = testCollection1.iterator();

        while(testCollection1Itr.hasNext()) {
            testCollection1Itr.next();
        }

        assertEquals(10, testCollection1.size());
        assertFalse(testCollection1Itr.hasNext());
        assertFalse(testCollection1.isEmpty());
        /* JUnit5 java8+
        // next() should throw an exception as there are no more nodes
        assertThrows(NoSuchElementException.class, () -> { testCollection1Itr.next(); });
        */

        try {
            testCollection1Itr.next();
            fail("Exception should be caught; This should not print.");
        } catch (NoSuchElementException e) {
            System.out.println("NextWhenNone()+CachedIteratorCollection: NoSuchElementException caught successfully." + e);
        }

        // Compare behavior with LinkedList object
        LinkedList<String> testCollection2 = new LinkedList<>();

        AddNObjectsLL(testCollection2, 10);

        Iterator<String> testCollection2Itr = testCollection2.iterator();

        while(testCollection2Itr.hasNext()) {
            testCollection2Itr.next();
        }

        assertEquals(10, testCollection2.size());
        assertFalse(testCollection2Itr.hasNext());
        assertFalse(testCollection2.isEmpty());
        /* JUnit5 java8+
        // next() should throw an exception as there are no more nodes
        assertThrows(NoSuchElementException.class, () -> { testCollection2Itr.next(); });
        */

        try {
            testCollection2Itr.next();
            fail("Exception should be caught; This should not print.");
        } catch (NoSuchElementException e) {
            System.out.println("NextWhenNone()+LinkedList: NoSuchElementException caught successfully." + e);
        }
    }

    //@DisplayName("Add, Remove All and Add Again Test")
    @Test
    public void ReAdd() {
        CachedIteratorCollection<String> testCollection1 = new CachedIteratorCollection<>();

        AddNObjects(testCollection1, 10);

        Iterator<String> testCollection1Itr = testCollection1.iterator();

        String testString = "";

        while (testCollection1Itr.hasNext()) {
            testString += testCollection1Itr.next();
        }

        assertEquals("test0test1test2test3test4test5test6test7test8test9", testString);
        assertEquals(10, testCollection1.size());

        // Reset and remove all
        testCollection1Itr = testCollection1.iterator();

        while(testCollection1Itr.hasNext()) {
            testCollection1Itr.next();
            testCollection1Itr.remove();
        }

        assertEquals(0, testCollection1.size());
        assertFalse(testCollection1Itr.hasNext());
        assertTrue(testCollection1.isEmpty());

        // Re-add
        AddNObjects(testCollection1, 10);
        testCollection1Itr = testCollection1.iterator();

        assertEquals(10, testCollection1.size());
        assertTrue("test0".equals(testCollection1Itr.next()));
        assertTrue(testCollection1Itr.hasNext());
        assertFalse(testCollection1.isEmpty());

        // Compare behavior with LinkedList object
        LinkedList<String> testCollection2 = new LinkedList<>();

        AddNObjectsLL(testCollection2, 10);

        Iterator<String> testCollection2Itr = testCollection2.iterator();

        testString = "";

        while (testCollection2Itr.hasNext()) {
            testString += testCollection2Itr.next();
        }

        assertEquals("test0test1test2test3test4test5test6test7test8test9", testString);
        assertEquals(10, testCollection2.size());

        testCollection2Itr = testCollection2.iterator();

        while(testCollection2Itr.hasNext()) {
            testCollection2Itr.next();
            testCollection2Itr.remove();
        }

        assertEquals(0, testCollection2.size());
        assertFalse(testCollection2Itr.hasNext());
        assertTrue(testCollection2.isEmpty());

        // Re-add
        AddNObjectsLL(testCollection2, 10);
        testCollection2Itr = testCollection2.iterator();

        assertEquals(10, testCollection2.size());
        assertTrue("test0".equals(testCollection2Itr.next()));
        assertTrue(testCollection2Itr.hasNext());
        assertFalse(testCollection2.isEmpty());
    }
}
