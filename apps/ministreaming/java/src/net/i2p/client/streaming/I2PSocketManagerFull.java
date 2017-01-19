package net.i2p.client.streaming;

/**
 *  Moved to net.i2p.client.streaming.impl in 0.9.10.
 *  Restored as a dummy class in 0.9.12, as i2phex imports it and
 *  calls I2PSocketManagerFull.class.getName() to pass to I2PSocketManagerFactory.
 *  I2PSocketManagerFactory ignores the class setting as of 0.9.12.
 *  This does not implement I2PSocketManager. Do not use.
 *
 *  @since 0.9.12
 *  @deprecated
 */
@Deprecated
public class I2PSocketManagerFull {}
