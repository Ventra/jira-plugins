package ru.ventra.jira.plugins.mailaddresshandler;

import javax.mail.Address;
import javax.mail.Message;
import javax.mail.MessagingException;

import com.atlassian.jira.plugins.mail.handlers.NonQuotedCommentHandler;

public class NonQuotedCommentAddressHandler extends NonQuotedCommentHandler {
	@Override
	protected String getEmailBody(Message message) throws MessagingException {
		String emailBody = super.getEmailBody(message);
		Address[] senders = message.getFrom();
		StringBuilder sb = new StringBuilder();
		sb.append("Sender: ");
		for (Address sender : senders) {
			sb.append(sender.toString());
			sb.append(" ");
		}
		sb.append("\n----\n");
		sb.append(emailBody);
		return sb.toString();
	}
}
