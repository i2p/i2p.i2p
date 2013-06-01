/* CoordinatorListener.java - Callback when a peer changes state

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
 * Callback used when some peer changes state.
 */
interface CoordinatorListener
{
  /**
   * Called when the PeerCoordinator notices a change in the state of a peer.
   */
  void peerChange(PeerCoordinator coordinator, Peer peer);

  /**
   * Called when the PeerCoordinator got the MetaInfo via magnet.
   * @since 0.8.4
   */
  void gotMetaInfo(PeerCoordinator coordinator, MetaInfo metainfo);

  /**
   * Is this number of uploaders over the per-torrent limit?
   */
  public boolean overUploadLimit(int uploaders);

  /**
   * Are we currently over the upstream bandwidth limit?
   */
  public boolean overUpBWLimit();

  /**
   * Is the total (in Bps) over the upstream bandwidth limit?
   */
  public boolean overUpBWLimit(long total);

  public void addMessage(String message);
}
