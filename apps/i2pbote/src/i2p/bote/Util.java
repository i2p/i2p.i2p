package i2p.bote;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;
import java.util.concurrent.ThreadFactory;

import net.i2p.client.I2PClient;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.data.DataFormatException;

public class Util {
	private static final int BUFFER_SIZE = 32 * 1024;

	private Util() { }
	
	public static void copyBytes(InputStream inputStream, OutputStream outputStream) throws IOException {
		byte[] buffer = new byte[BUFFER_SIZE];
		boolean done = false;
		while (!done) {
			int bytesRead = inputStream.read(buffer);
			outputStream.write(buffer);
			if (bytesRead < 0)
				done = true;
		}
	}

	public static void writeKeyStream(I2PSession i2pSession, OutputStream outputStream) throws DataFormatException, IOException {
		i2pSession.getMyDestination().writeBytes(outputStream);
		i2pSession.getDecryptionKey().writeBytes(outputStream);
		i2pSession.getPrivateKey().writeBytes(outputStream);
	}

	public static InputStream createKeyStream(I2PSession i2pSession) throws DataFormatException, IOException {
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		writeKeyStream(i2pSession, outputStream);
		return new ByteArrayInputStream(outputStream.toByteArray());
	}	

	public static I2PSession createNewSession(I2PClient i2pClient, I2PSession existingSession, Properties options) throws I2PSessionException, DataFormatException, IOException {
	    return I2PClientFactory.createClient().createSession(createKeyStream(existingSession), options);
	}
	
    public static I2PSocketManager getSocketManager(I2PSession i2pSession) throws DataFormatException, IOException {
        ByteArrayOutputStream keyOutputStream = new ByteArrayOutputStream();
        Util.writeKeyStream(i2pSession, keyOutputStream);
        InputStream keyInputStream = new ByteArrayInputStream(keyOutputStream.toByteArray());
        return I2PSocketManagerFactory.createManager(keyInputStream);
    }

    public static byte[] readInputStream(InputStream inputStream) throws IOException {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[32*1024];
        while (true) {
            int bytesToRead = Math.min(inputStream.available(), buffer.length);
            if (bytesToRead <= 0)
                break;
            else {
                int bytesRead = inputStream.read(buffer, 0, bytesToRead);
                byteStream.write(buffer, 0, bytesRead);
            }
        }
        return byteStream.toByteArray();
    }

    public static ThreadFactory createThreadFactory(final String threadName, final int stackSize) {
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                return new Thread(null, runnable, threadName, stackSize);
            }
        };
    }
}