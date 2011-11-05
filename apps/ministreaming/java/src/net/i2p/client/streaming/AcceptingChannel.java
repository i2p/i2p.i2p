package net.i2p.client.streaming;

import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.I2PException;
import java.net.ConnectException;
import java.nio.channels.SelectableChannel;

public abstract class AcceptingChannel extends SelectableChannel {
    abstract I2PSocket accept() throws I2PException, ConnectException;
    I2PSocketManager _socketManager;
    AcceptingChannel(I2PSocketManager manager) {
        this._socketManager = manager;
    }
}
