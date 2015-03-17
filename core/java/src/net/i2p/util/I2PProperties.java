package net.i2p.util;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * This class contains a number of properties ((key,value)-pairs).
 * Additionally, it adds the possibility for callbacks,
 * to allow immediate response to changing properties.
 * @author Mathiasdm
 *
 */
public class I2PProperties extends Properties {
	
	/**
	 * Keep a list of callbacks to contact the interested parties
	 * that want to know about property changes.
	 */
	private final List<I2PPropertyCallback> _callbacks = new CopyOnWriteArrayList<I2PPropertyCallback>();

	public I2PProperties() {
		super();
	}

	public I2PProperties(Properties defaults) {
		super(defaults);
	}
	
	public void addCallBack(I2PPropertyCallback callback) {
		_callbacks.add(callback);
	}
	
	public void removeCallBack(I2PPropertyCallback callback) {
		_callbacks.remove(callback);
	}
	
	public Object setProperty(String key, String value) {
		Object returnValue = super.setProperty(key, value);
		for(I2PPropertyCallback callback: _callbacks) {
			callback.propertyChanged(key, value);
		}
		return returnValue;
	}
	
	public interface I2PPropertyCallback {
		
		public void propertyChanged(String key, String value);
		
	}

}
