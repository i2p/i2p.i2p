package net.i2p.router.crypto.ratchet;

/**
 * Something that looks for SessionTags.
 *
 * @since 0.9.44
 */
interface SessionTagListener {

    /**
     *  Map the tag to this tagset.
     *
     *  @return true if added, false if dup
     */
    public boolean addTag(RatchetSessionTag tag, RatchetTagSet ts);

    /**
     *  Remove the tag associated with this tagset.
     */
    public void expireTag(RatchetSessionTag tag, RatchetTagSet ts);
}
