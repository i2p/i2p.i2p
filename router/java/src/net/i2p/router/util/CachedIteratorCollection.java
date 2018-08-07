// The Node class below is derived from Java's LinkedList.java
/*
 * Copyright (c) 1997, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package net.i2p.router.util;

import java.util.AbstractCollection;
import java.util.Iterator;
import java.util.NoSuchElementException;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * Extend java.util.AbstractCollection to create a collection that can be
 * iterated over without creation of a new object
 *
 * @since 0.9.36
 *
 */

public class CachedIteratorCollection<E> extends AbstractCollection<E> {

    // FOR DEBUGGING & LOGGING PURPOSES
    //Log log = I2PAppContext.getGlobalContext().logManager().getLog(CachedIteratorCollection.class);

    // Cached Iterator object
    private final CachedIterator iterator = new CachedIterator();

    // Size of the AbstractCollectionTest object
    private transient int size = 0;

    /**
     * Node object that contains:
     * (1) Data object
     * (2) Link to previous Node object
     * (3) Link to next Node object
     */
    private static class Node<E> {
        E item;
        Node<E> next;
        Node<E> prev;

        Node(Node<E> prev, E element) {
            this.item = element;
            this.prev = prev;
            this.next = null;
        }
    }

    // First Node in the AbstractCollectionTest object
    private transient Node<E> first = null;

    // Last Node in the AbstractCollectionTest object
    private transient Node<E> last = null;

    /**
     * Default constructor
     */
    public CachedIteratorCollection() {
    }

    /**
     * Adds a data object (element) as a Node and sets previous/next pointers accordingly
     *
     */
    @Override
    public boolean add(E element) {
        final Node<E> newNode = new Node<>(last, element);
        if (this.size == 0) {
            this.first = newNode;
        } else {
            this.last.next = newNode;
        }
        this.last = newNode;
        this.size++;
        //log.debug("CachedIteratorAbstractCollection: Element added");
        return true;
    }

    /**
     *  Clears the AbstractCollectionTest object, all pointers reset to 'null'
     *
     */
    @Override
    public void clear() {
        this.first = null;
        this.last = null;
        this.size = 0;
        iterator.reset();
        //log.debug("CachedIteratorAbstractCollection: Cleared");
    }

    /**
     *  Iterator: Resets and returns CachedIterator
     *
     */
    public Iterator<E> iterator() {
        iterator.reset();
        return iterator;
    }

    /**
     *  Inner CachedIterator class - implements hasNext(), next() & remove()
     *
     */
    private class CachedIterator implements Iterator<E> {

        private transient boolean nextCalled;

        // Iteration Index
        private transient Node<E> itrIndexNode = first;

        // Methods to support iteration

        /**
         * Reset iteration
         */
        private void reset() {
            itrIndexNode = first;
            nextCalled = false;
        }

        /**
         *  If nextCalled is true (i.e. next() has been called at least once),
         *  remove() will remove the last returned Node
         *
         */
        @Override
        public void remove() {
            if (nextCalled) {
                // Are we at the end of the collection? If so itrIndexNode will
                // be null
                if (itrIndexNode != null) {
                    // The Node we are trying to remove is itrIndexNode.prev
                    // Is there a Node before itrIndexNode.prev?
                    if (itrIndexNode != first.next) {
                        // Set current itrIndexNode's prev to Node N-2
                        itrIndexNode.prev = itrIndexNode.prev.prev;
                        // Then set Node N-2's next to current itrIndexNode,
                        // this drops all references to the Node being removed
                        itrIndexNode.prev.next = itrIndexNode;
                    } else {
                        // There is no N-2 Node, we are removing the first Node
                        // in the collection
                        itrIndexNode.prev = null;
                        first = itrIndexNode;
                    }
                } else {
                    // itrIndexNode is null, we are at the end of the collection
                    // Are there any items before the Node that is being removed?
                    if (last.prev != null) {
                        last.prev.next = null;
                        last = last.prev;
                    } else {
                        // There are no more items, clear() the collection
                        nextCalled = false;
                        clear();
                        //log.debug("CachedIteratorAbstractCollection: Element Removed");
                        return;
                    }
                }
                size--;
                nextCalled = false;
                //log.debug("CachedIteratorAbstractCollection: Element Removed");
            } else {
                throw new IllegalStateException();
            }
        }

        /**
         *  Returns true as long as current Iteration Index Node (itrIndexNode)
         *  is non-null
         *
         */
        public boolean hasNext() {
            return itrIndexNode != null;
        }

        /**
         * Returns the next node in the iteration
         *
         */
        public E next() {
            if (this.hasNext()) {
                Node<E> node = itrIndexNode;
                itrIndexNode = itrIndexNode.next;
                nextCalled = true;
                return node.item;
            } else {
                throw new NoSuchElementException();
            }
        }
    }

    /**
     * Return size of current LinkedListTest object
     */
    public int size() {
        return this.size;
    }
}
