package i2p.susi.webmail;


/**
 *  Listen for indication of new mail, maybe
 *  @since 0.9.13
 */
public interface NewMailListener {
    
	public void foundNewMail(boolean yes);

}
