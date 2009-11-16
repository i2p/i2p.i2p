<%@ page language="java" contentType="text/html; charset=UTF-8"
    pageEncoding="UTF-8"%>
<!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN" "http://www.w3.org/TR/html4/loose.dtd">

<jsp:include page="header.jsp">
    <jsp:param name="title" value="No Identity"/>
</jsp:include>

<div class="main">
    <h2>No Email Identity Defined</h2>
    <p>
    In order to receive email from other people, you need to create an email identity
    first.
    </p><p>
    This is similar to traditional email where you first have to set up an email account
    with an email provider. The difference is that in I2P-Bote, there is no provider that
    can read all your email because I2P-Bote stores all emails encrypted on the network.
    </p><p>
    I2P-Bote automatically decrypts emails sent to you, using the email identity you
    created.
    </p><p>
    An email identity has a secret key, and a public key. Anybody who has the secret key
    can download and decrypt your emails, so you should never give it to anybody. The
    public key, on the other hand, allows people to send email to you. Think of it as
    the equivalent of a traditional email address. Give it to everybody you want to be
    able to contact you, or make it public.
    </p><p>
    [TODO paragraph about spam, HashCash].
    </p><p>
    You will find that email identities are long combinations of numbers and letters,
    and that they are impossible to memorize. I2P-Bote lets you assign an email address
    to an email identity to make it easier for you and your contacts to remember.
    </p><p>
    [TODO name attribute, address attribute].
    The public addressbook should not be 100% trusted. Only destination keys are secure.
    </p><p>
    [TODO private address book, address directory].
    <form action="editIdentity.jsp?new=true" method="POST">
        <button type="submit" value="New">Create a New Email Identity</button>
    </form>
</div>

<jsp:include page="footer.jsp"/>