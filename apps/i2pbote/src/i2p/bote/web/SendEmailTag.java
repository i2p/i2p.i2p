package i2p.bote.web;

import i2p.bote.I2PBote;
import i2p.bote.packet.Email;

import java.io.IOException;

import javax.mail.Message.RecipientType;
import javax.mail.internet.InternetAddress;
import javax.servlet.jsp.JspWriter;
import javax.servlet.jsp.PageContext;
import javax.servlet.jsp.tagext.SimpleTagSupport;

import net.i2p.util.Log;

public class SendEmailTag extends SimpleTagSupport {
    // TODO make all Log instances final
    private Log log = new Log(SendEmailTag.class);
	private String recipientAddress;
	private String message;

	public void doTag() {
		PageContext pageContext = (PageContext) getJspContext();
		JspWriter out = pageContext.getOut();
		
		Email email = new Email();
		String statusMessage;
		try {
			email.addRecipient(RecipientType.TO, new InternetAddress(recipientAddress));
			email.setContent(message, "text/html");
			I2PBote.getInstance().sendEmail(email);
			statusMessage = "Email has been queued for sending.";
		}
		catch (Exception e) {
			statusMessage = "Error sending email: " + e.getLocalizedMessage();
			log.error("Error sending email", e);
		}

		try {
			out.println(statusMessage);
		} catch (IOException e) {
			log.error("Can't write output to HTML page", e);
		}
	}

	public void setRecipient(String recipient) {
		this.recipientAddress = recipient;
	}

	public String getRecipient() {
		return recipientAddress;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	public String getMessage() {
		return message;
	}
}