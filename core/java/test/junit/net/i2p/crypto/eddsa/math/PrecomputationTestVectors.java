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
package net.i2p.crypto.eddsa.math;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import net.i2p.crypto.eddsa.Utils;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveSpec;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;

public class PrecomputationTestVectors {
    // Test files were generated using base.py and base2.py from ref10
    // (by printing hex(x%q) instead of the radix-255 representation).
    static GroupElement[][] testPrecmp = getPrecomputation("basePrecmp");
    static GroupElement[] testDblPrecmp = getDoublePrecomputation("baseDblPrecmp");

    public static GroupElement[][] getPrecomputation(String fileName) {
        EdDSANamedCurveSpec ed25519 = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);
        Curve curve = ed25519.getCurve();
        Field field = curve.getField();
        GroupElement[][] precmp = new GroupElement[32][8];
        BufferedReader file = null;
        int row = 0, col = 0;
        try {
            InputStream is = PrecomputationTestVectors.class.getResourceAsStream(fileName);
            if (is == null)
                throw new IOException("Resource not found: " + fileName);
            file = new BufferedReader(new InputStreamReader(is));
            String line;
            while ((line = file.readLine()) != null) {
                if (line.equals(" },"))
                    col += 1;
                else if (line.equals("},")) {
                    col = 0;
                    row += 1;
                } else if (line.startsWith("  { ")) {
                    String ypxStr = line.substring(4, line.lastIndexOf(' '));
                    FieldElement ypx = field.fromByteArray(
                            Utils.hexToBytes(ypxStr));
                    line = file.readLine();
                    String ymxStr = line.substring(4, line.lastIndexOf(' '));
                    FieldElement ymx = field.fromByteArray(
                            Utils.hexToBytes(ymxStr));
                    line = file.readLine();
                    String xy2dStr = line.substring(4, line.lastIndexOf(' '));
                    FieldElement xy2d = field.fromByteArray(
                            Utils.hexToBytes(xy2dStr));
                    precmp[row][col] = GroupElement.precomp(curve,
                            ypx, ymx, xy2d);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (file != null) try { file.close(); } catch (IOException e) {}
        }
        return precmp;
    }

    public static GroupElement[] getDoublePrecomputation(String fileName) {
        EdDSANamedCurveSpec ed25519 = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);
        Curve curve = ed25519.getCurve();
        Field field = curve.getField();
        GroupElement[] dblPrecmp = new GroupElement[8];
        BufferedReader file = null;
        int row = 0;
        try {
            InputStream is = PrecomputationTestVectors.class.getResourceAsStream(fileName);
            if (is == null)
                throw new IOException("Resource not found: " + fileName);
            file = new BufferedReader(new InputStreamReader(is));
            String line;
            while ((line = file.readLine()) != null) {
                if (line.equals(" },")) {
                    row += 1;
                } else if (line.startsWith("  { ")) {
                    String ypxStr = line.substring(4, line.lastIndexOf(' '));
                    FieldElement ypx = field.fromByteArray(
                            Utils.hexToBytes(ypxStr));
                    line = file.readLine();
                    String ymxStr = line.substring(4, line.lastIndexOf(' '));
                    FieldElement ymx = field.fromByteArray(
                            Utils.hexToBytes(ymxStr));
                    line = file.readLine();
                    String xy2dStr = line.substring(4, line.lastIndexOf(' '));
                    FieldElement xy2d = field.fromByteArray(
                            Utils.hexToBytes(xy2dStr));
                    dblPrecmp[row] = GroupElement.precomp(curve,
                            ypx, ymx, xy2d);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (file != null) try { file.close(); } catch (IOException e) {}
        }
        return dblPrecmp;
    }
}
