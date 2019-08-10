/**
 * EdDSA-Java by str4d
 *
 * To the extent possible under law, the person who associated CC0 with
 * EdDSA-Java has waived all copyright and related or neighboring rights
 * to EdDSA-Java.
 *
 * You should have received a copy of the CC0 legalcode along with this
 * work. If not, see <https://creativecommons.org/publicdomain/zero/1.0/>.
 *
 */
package net.i2p.crypto.eddsa;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class Ed25519TestVectors {
    public static class TestTuple {
        public static int numCases;
        public int caseNum;
        public byte[] seed;
        public byte[] pk;
        public byte[] message;
        public byte[] sig;

        public TestTuple(String line) {
            caseNum = ++numCases;
            String[] x = line.split(":");
            seed = Utils.hexToBytes(x[0].substring(0, 64));
            pk = Utils.hexToBytes(x[1]);
            message = Utils.hexToBytes(x[2]);
            sig = Utils.hexToBytes(x[3].substring(0, 128));
        }
    }

    public static Collection<TestTuple> testCases = getTestData("test.data");

    public static Collection<TestTuple> getTestData(String fileName) {
        List<TestTuple> testCases = new ArrayList<TestTuple>();
        BufferedReader file = null;
        try {
            InputStream is = Ed25519TestVectors.class.getResourceAsStream(fileName);
            if (is == null)
                throw new IOException("Resource not found: " + fileName);
            file = new BufferedReader(new InputStreamReader(is));
            String line;
            while ((line = file.readLine()) != null) {
                testCases.add(new TestTuple(line));
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (file != null) try { file.close(); } catch (IOException e) {}
        }
        return testCases;
    }
}
