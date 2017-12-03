package net.i2p.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * A queue of messages, where each has an ID number.
 * Provide the ID back to the clear call, so you don't
 * erase messages you haven't seen yet.
 *
 * Thread-safe.
 *
 * @since 0.9.33 adapted from SnarkManager
 */
public class UIMessages {

    private final int _maxSize;
    private int _count;
    private final LinkedList<Message> _messages;

    /**
     *  @param maxSize
     */
    public UIMessages(int maxSize) {
        if (maxSize < 1)
            throw new IllegalArgumentException();
        _maxSize = maxSize;
        _messages = new LinkedList<Message>();
    }

    /**
     *  Will remove an old message if over the max size.
     *  Use if it does not include a link.
     *  Escapes '&lt;' and '&gt;' before queueing
     *
     *  @return the message id
     */
    public int addMessage(String message) {
        return addMessageNoEscape(message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"));
    }

    /**
     * Use if it includes a link.
     * Does not escape '&lt;' and '&gt;' before queueing
     *
     *  @return the message id
     */
    public synchronized int addMessageNoEscape(String message) {
        _messages.offer(new Message(_count++, message));
        while (_messages.size() > _maxSize) {
            _messages.poll();
        }
        return _count;
    }

    /**
     * The ID of the last message added, or -1 if never.
     */
    public synchronized int getLastMessageID() {
        return _count - 1;
    }
    
    /**
     * Newest last, or empty list.
     * Provide id of last one back to clearThrough().
     * @return a copy
     */
    public synchronized List<Message> getMessages() {
        if (_messages.isEmpty())
            return Collections.emptyList();
        return new ArrayList<Message>(_messages);
    }
    
    /** clear all */
    public synchronized void clear() {
        _messages.clear();
    }
    
    /** clear all up to and including this id */
    public synchronized void clearThrough(int id) {
        Message m = _messages.peekLast();
        if (m == null) {
            // nothing to do
        } else if (m.id <= id) {
            // easy way
            _messages.clear();
        } else {
            for (Iterator<Message> iter = _messages.iterator(); iter.hasNext(); ) {
                Message msg = iter.next();
                if (msg.id > id)
                    break;
                iter.remove();
            }
        }
    }

    public static class Message {
        public final int id;
        public final String message;

        private Message(int i, String msg) {
            id = i;
            message = msg;
        }

        @Override
        public String toString() {
            return message;
        }
    }
}
