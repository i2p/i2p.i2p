package net.i2p.crypto;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;

import net.i2p.I2PAppContext;
import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;
import net.i2p.util.Log;

/**
 * Handles DSA signing and verification of I2P update archives.
 * 
 * @author smeghead
 */
public class TrustedUpdate {

	private static byte[] I2P_PUBLICKEY = { 'p', 'k' };

    private I2PAppContext _context;
    private Log           _log;

    public TrustedUpdate() {
        _context = I2PAppContext.getGlobalContext();
        _log = _context.logManager().getLog(TrustedUpdate.class);
    }

	public static void main(String[] args) {
		// If no context is defined, don't define one.
		// Expose verify(inputFile, publicKeyFile) via cli param
	}

	/**
	 * Reads the version string from a signed I2P update file.
	 * 
	 * @param inputFile A signed I2P update file.
	 * 
	 * @return The update version string read, or an empty string if no version
	 *         string is present.
	 */
	public String getUpdateVersion(String inputFile) {
		String updateVersion = null;
		byte[] data = readFileBytes(inputFile, 0, 16);
		try {
			updateVersion = new String(data, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			// If this ever gets called, you need a new JVM.
		}
		return updateVersion;
	}

	/**
	 * Uses the given private key to sign the given input file with DSA. The
	 * output will be a binary file where the first 16 bytes are the I2P
	 * update's version string encoded in UTF-8 (padded with trailing
	 * <code>0h</code> characters if necessary), the next 40 bytes are the
	 * resulting DSA signature, and the remaining bytes are the input file.
	 * 
	 * @param inputFile      The file to be signed.
	 * @param outputFile     The signed file to write.
	 * @param privateKeyFile The name of the file containing the private key to
	 *                       sign <code>inputFile</code> with.
	 * @param updateVersion  The version number of the I2P update. If this
	 *                       string is longer than 16 characters it will be
	 *                       truncated.
	 * 
	 * @return An instance of {@link net.i2p.data.Signature}.
	 */
	public Signature sign(String inputFile, String outputFile, String privateKeyFile, String updateVersion) {
		byte[] headerUpdateVersion = {
				0x00, 0x00, 0x00, 0x00,
				0x00, 0x00, 0x00, 0x00,
				0x00, 0x00, 0x00, 0x00,
				0x00, 0x00, 0x00, 0x00 };
		byte[] updateVersionBytes = {};
		if (updateVersion.length() > 16)
			updateVersion = updateVersion.substring(0, 16);
		try {
			updateVersionBytes = updateVersion.getBytes("UTF-8");
		} catch (UnsupportedEncodingException e) {
			// If this ever gets called, you need a new JVM.
		}
		for (int i = 0; i < updateVersionBytes.length; i++)
			headerUpdateVersion[i] = updateVersionBytes[i];
		byte[] data = readFileBytes(inputFile, 0, (int) new File(inputFile).length());
		Signature signature = DSAEngine.getInstance().sign(data, new SigningPrivateKey(readFileBytes(privateKeyFile, 0, (int) new File(privateKeyFile).length()-1)));
		FileOutputStream fileOutputStream = null;
		try {
			fileOutputStream = new FileOutputStream(outputFile);
			fileOutputStream.write(headerUpdateVersion);
			fileOutputStream.write(signature.getData());
			fileOutputStream.write(data);
			fileOutputStream.close();
		} catch (IOException ioe) {
			if (_log.shouldLog(Log.WARN))
				_log.log(Log.WARN, "Error writing signed I2P update file " + outputFile, ioe);
		}

		return signature;
	}

	/**
	 * Verifies the DSA signature of a signed I2P update.
	 * 
	 * @param inputFile The signed update file to check.
	 * 
	 * @return <code>true</code> if the file has a valid signature.
	 */
	public boolean verify(String inputFile) {
		DSAEngine.getInstance().verifySignature(new Signature(readFileBytes(inputFile, 16, 55)),
				readFileBytes(inputFile, 56, (int) new File(inputFile).length()-57),
				new SigningPublicKey(I2P_PUBLICKEY));
		return false;
	}

	/**
	 * Verifies the DSA signature of a signed I2P update.
	 * 
	 * @param inputFile     The signed update file to check.
	 * @param publicKeyFile The public key to use for verification.
	 * 
	 * @return <code>true</code> if the file has a valid signature. 
	 */
	public boolean verify(String inputFile, String publicKeyFile) {
		DSAEngine.getInstance().verifySignature(new Signature(readFileBytes(inputFile, 16, 55)),
				readFileBytes(inputFile, 56, (int) new File(inputFile).length()-57),
				new SigningPublicKey(readFileBytes(publicKeyFile, 0, (int) new File(publicKeyFile).length()-1)));
		return false;
	}

	private byte[] readFileBytes(String inputFile, int offset, int length) {
		byte[] bytes = new byte[length];
		FileInputStream fileInputStream = null;

		try {
			fileInputStream = new FileInputStream(inputFile);
			fileInputStream.read(bytes, offset, length);
			fileInputStream.close();
		} catch (FileNotFoundException fnfe) {
			if (_log.shouldLog(Log.WARN))
				_log.log(Log.WARN, "File " + inputFile + " not found", fnfe);
		} catch (IOException ioe) {
			if (_log.shouldLog(Log.WARN))
				_log.log(Log.WARN, "Error reading file " + inputFile, ioe);
		}

		return bytes;
	}
}
