package net.i2p.stat;

public class SizeTest {
    public static void main(String args[]) {
        testRateSize(100); //117KB
        testRateSize(100000); // 4.5MB
        testRateSize(440000); // 44MB
        //testFrequencySize(100); // 114KB
        //testFrequencySize(100000); // 5.3MB
        //testFrequencySize(1000000); // 52MB
    }

    private static void testRateSize(int num) {
        Runtime.getRuntime().gc();
        Rate rate[] = new Rate[num];
        long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long usedPer = used / num;
        System.out
                  .println(num + ": create array - Used: " + used + " bytes (or " + usedPer + " bytes per array entry)");

        int i = 0;
        try {
            for (; i < num; i++)
                rate[i] = new Rate(1234);
        } catch (OutOfMemoryError oom) {
            rate = null;
            Runtime.getRuntime().gc();
            System.out.println("Ran out of memory when creating rate " + i);
            return;
        }
        Runtime.getRuntime().gc();
        long usedObjects = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        usedPer = usedObjects / num;
        System.out.println(num + ": create objects - Used: " + usedObjects + " bytes (or " + usedPer
                           + " bytes per rate)");
        rate = null;
        Runtime.getRuntime().gc();
    }

    private static void testFrequencySize(int num) {
        Runtime.getRuntime().gc();
        Frequency freq[] = new Frequency[num];
        long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long usedPer = used / num;
        System.out
                  .println(num + ": create array - Used: " + used + " bytes (or " + usedPer + " bytes per array entry)");

        for (int i = 0; i < num; i++)
            freq[i] = new Frequency(1234);
        Runtime.getRuntime().gc();
        long usedObjects = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        usedPer = usedObjects / num;
        System.out.println(num + ": create objects - Used: " + usedObjects + " bytes (or " + usedPer
                           + " bytes per frequency)");
        freq = null;
        Runtime.getRuntime().gc();
    }
}