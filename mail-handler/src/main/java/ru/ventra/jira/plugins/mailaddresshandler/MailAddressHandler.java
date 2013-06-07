package ru.ventra.jira.plugins.mailaddresshandler;

import java.io.IOException;
import java.util.Map;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Part;

import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.plugins.mail.handlers.AbstractMessageHandler;
import com.atlassian.jira.plugins.mail.handlers.NonQuotedCommentHandler;
import com.atlassian.jira.service.util.ServiceUtils;
import com.atlassian.jira.service.util.handler.MessageHandlerContext;
import com.atlassian.jira.service.util.handler.MessageHandlerErrorCollector;
import com.atlassian.jira.service.util.handler.MessageUserProcessor;
import com.atlassian.mail.MailUtils;

@SuppressWarnings("deprecation")
public class MailAddressHandler extends AbstractMessageHandler {

	public String projectKey;
	public String issueType;
	public String stripquotes;
	public static final String KEY_PROJECT = "project";
	public static final String KEY_ISSUETYPE = "issuetype";
	public static final String KEY_QUOTES = "stripquotes";

	public boolean handleMessage(Message message, MessageHandlerContext context) throws MessagingException {
		String subject = message.getSubject();

		if (!canHandleMessage(message, context.getMonitor())) {
			if (this.log.isDebugEnabled()) {
				this.log.debug("Cannot handle message '" + subject + "'.");
			}
			return this.deleteEmail;
		}

		if (this.log.isDebugEnabled()) {
			this.log.debug("Looking for Issue Key in subject '" + subject + "'.");
		}
		Issue issue = ServiceUtils.findIssueObjectInString(subject);

		if (issue == null) {
			this.log.debug("Issue Key not found in subject '" + subject + "'. Inspecting the in-reply-to message ID.");
			issue = getAssociatedIssue(message);
		}

		if (issue != null) {
			if (this.log.isDebugEnabled()) {
				this.log.debug("Issue '" + issue.getKey() + "' found for email '" + subject + "'.");
			}
			boolean doDelete;
			if ((this.stripquotes == null) || ("false".equalsIgnoreCase(this.stripquotes))) {
				FullCommentAddressHandler fc = new FullCommentAddressHandler() {
					protected MessageUserProcessor getMessageUserProcessor() {
						return MailAddressHandler.this.getMessageUserProcessor();
					}
				};
				fc.init(this.params, context.getMonitor());
				doDelete = fc.handleMessage(message, context);
			} else {
				NonQuotedCommentHandler nq = new NonQuotedCommentHandler() {
					protected MessageUserProcessor getMessageUserProcessor() {
						return MailAddressHandler.this.getMessageUserProcessor();
					}
				};
				nq.init(this.params, context.getMonitor());
				doDelete = nq.handleMessage(message, context);
			}
			return doDelete;
		}

		if (this.log.isDebugEnabled()) {
			this.log.debug("No Issue found for email '" + subject + "' - creating a new Issue.");
		}

		CreateIssueAddressHandler createIssueHandler = new CreateIssueAddressHandler() {
			protected MessageUserProcessor getMessageUserProcessor() {
				return MailAddressHandler.this.getMessageUserProcessor();
			}
		};
		createIssueHandler.init(this.params, context.getMonitor());
		return createIssueHandler.handleMessage(message, context);
	}

	public void init(Map<String, String> params, MessageHandlerErrorCollector errorCollector) {
		this.log.debug("CreateOrCommentHandler.init(params: " + params + ")");

		super.init(params, errorCollector);

		if (params.containsKey("project")) {
			this.projectKey = ((String) params.get("project"));
		}

		if (params.containsKey("issuetype")) {
			this.issueType = ((String) params.get("issuetype"));
		}

		if (params.containsKey("stripquotes")) {
			this.stripquotes = ((String) params.get("stripquotes"));
		}
	}

	protected boolean attachPlainTextParts(Part part) throws MessagingException, IOException {
		return !MailUtils.isContentEmpty(part);
	}

	protected boolean attachHtmlParts(Part part) throws MessagingException, IOException {
		return false;
	}

}
