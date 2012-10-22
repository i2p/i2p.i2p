/* CompleteListener - Callback for Snark events
   
   Copyright (C) 2003 Mark J. Wielaard

   This file is part of Snark.
   
   This program is free software; you can redistribute it and/or modify
   it under the terms of the GNU General Public License as published by
   the Free Software Foundation; either version 2, or (at your option)
   any later version.
 
   This program is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
   GNU General Public License for more details.
 
   You should have received a copy of the GNU General Public License
   along with this program; if not, write to the Free Software Foundation,
   Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
*/

package org.klomp.snark;

/**
 *  Callback for Snark events.
 *  @since 0.9.4 moved from Snark.java
 */
public interface CompleteListener {
    public void torrentComplete(Snark snark);
    public void updateStatus(Snark snark);

    /**
     * We transitioned from magnet mode, we have now initialized our
     * metainfo and storage. The listener should now call getMetaInfo()
     * and save the data to disk.
     *
     * @return the new name for the torrent or null on error
     * @since 0.8.4
     */
    public String gotMetaInfo(Snark snark);

    /**
     * @since 0.9
     */
    public void fatal(Snark snark, String error);

    /**
     * @since 0.9.2
     */
    public void addMessage(Snark snark, String message);

    /**
     * @since 0.9.4
     */
    public void gotPiece(Snark snark);

    // not really listeners but the easiest way to get back to an optional SnarkManager
    public long getSavedTorrentTime(Snark snark);
    public BitField getSavedTorrentBitField(Snark snark);
}
