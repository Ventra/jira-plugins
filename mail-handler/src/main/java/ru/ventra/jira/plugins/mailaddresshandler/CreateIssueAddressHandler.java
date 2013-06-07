package ru.ventra.jira.plugins.mailaddresshandler;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.mail.Address;
import javax.mail.Message;
import javax.mail.MessagingException;

import org.ofbiz.core.entity.GenericValue;

import com.atlassian.crowd.embedded.api.User;
import com.atlassian.jira.ComponentManager;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.config.ConstantsManager;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.IssueFactory;
import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.issue.MutableIssue;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.SummarySystemField;
import com.atlassian.jira.issue.priority.Priority;
import com.atlassian.jira.issue.security.IssueSecurityLevelManager;
import com.atlassian.jira.plugin.assignee.AssigneeResolver;
import com.atlassian.jira.plugins.mail.handlers.CreateIssueHandler;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.security.PermissionManager;
import com.atlassian.jira.service.util.handler.MessageHandlerContext;
import com.atlassian.jira.util.ErrorCollection;
import com.atlassian.jira.util.I18nHelper;
import com.atlassian.jira.util.SimpleErrorCollection;
import com.atlassian.jira.web.FieldVisibilityManager;
import com.atlassian.jira.web.action.issue.IssueCreationHelperBean;
import com.atlassian.jira.web.bean.I18nBean;
import com.atlassian.mail.MailUtils;
import com.opensymphony.util.TextUtils;

public class CreateIssueAddressHandler extends CreateIssueHandler {

	@Override
	public boolean handleMessage(Message message, MessageHandlerContext context) throws MessagingException {
		this.log.debug("CreateIssueHandler.handleMessage");

		if (!canHandleMessage(message, context.getMonitor())) {
			return this.deleteEmail;
		}

		try {
			User reporter = getReporter(message, context);
			if (reporter == null) {
				String error = getI18nBean().getText("admin.mail.no.default.reporter");
				context.getMonitor().warning(error);
				context.getMonitor().messageRejected(message, error);
				return false;
			}

			Project project = getProject(message);

			this.log.debug("Project = " + project);
			if (project == null) {
				String text = getI18nBean().getText("admin.mail.no.project.configured");
				context.getMonitor().warning(text);
				context.getMonitor().messageRejected(message, text);
				return false;
			}

			ErrorCollection errorCollection = new SimpleErrorCollection();

			I18nHelper i18nHelper = new I18nBean(Locale.ENGLISH);

			getIssueCreationHelperBean().validateLicense(errorCollection, i18nHelper);
			if (errorCollection.hasAnyErrors()) {
				context.getMonitor().warning(getI18nBean().getText("admin.mail.bad.license", errorCollection.getErrorMessages().toString()));
				return false;
			}

			if ((!getPermissionManager().hasPermission(11, project, reporter, true)) && (reporter.getDirectoryId() != -1L)) {
				String error = getI18nBean().getText("admin.mail.no.create.permission", reporter.getName());
				context.getMonitor().warning(error);
				context.getMonitor().messageRejected(message, error);
				return false;
			}

			this.log.debug("Issue Type Key = = " + this.issueType);

			if (!hasValidIssueType()) {
				context.getMonitor().warning(getI18nBean().getText("admin.mail.invalid.issue.type"));
				return false;
			}
			String summary = message.getSubject();
			if (!TextUtils.stringSet(summary)) {
				context.getMonitor().error(getI18nBean().getText("admin.mail.no.subject"));
				return false;
			}
			if (summary.length() > SummarySystemField.MAX_LEN.intValue()) {
				context.getMonitor().info("Truncating summary field because it is too long: " + summary);
				summary = summary.substring(0, SummarySystemField.MAX_LEN.intValue() - 3) + "...";
			}

			String priority = null;
			String description = null;

			if (!getFieldVisibilityManager().isFieldHiddenInAllSchemes(project.getId(), "priority", Collections.singletonList(this.issueType))) {
				priority = getPriority(message);
			}

			if (!getFieldVisibilityManager().isFieldHiddenInAllSchemes(project.getId(), "description", Collections.singletonList(this.issueType))) {
				description = getDescription(reporter, message);
			}

			MutableIssue issueObject = getIssueFactory().getIssue();
			issueObject.setProjectObject(project);
			issueObject.setSummary(summary);
			issueObject.setDescription(description);
			issueObject.setIssueTypeId(this.issueType);
			issueObject.setReporter(reporter);

			User assignee = null;
			if (this.ccAssignee) {
				assignee = getFirstValidAssignee(message.getAllRecipients(), project);
			}
			if (assignee == null) {
				assignee = getAssigneeResolver().getDefaultAssignee(issueObject, Collections.EMPTY_MAP);
			}

			if (assignee != null) {
				issueObject.setAssignee(assignee);
			}

			issueObject.setPriorityId(priority);

			setDefaultSecurityLevel(issueObject);

			Map fields = new HashMap();
			fields.put("issue", issueObject);

			MutableIssue originalIssue = getIssueManager().getIssueObject(issueObject.getId());

			List<CustomField> customFieldObjects = ComponentAccessor.getCustomFieldManager().getCustomFieldObjects(issueObject);
			for (CustomField customField : customFieldObjects) {
				issueObject.setCustomFieldValue(customField, customField.getDefaultValue(issueObject));
			}

			fields.put("originalissueobject", originalIssue);
			Issue issue = context.createIssue(reporter, issueObject);

			if (issue != null) {
				if (this.ccWatcher) {
					addCcWatchersToIssue(message, issue, reporter, context, context.getMonitor());
				}

				recordMessageId("ISSUE_CREATED_FROM_EMAIL", message, issue.getId(), context);
			}

			createAttachmentsForMessage(message, issue, context);

			return true;
		} catch (Exception e) {
			context.getMonitor().warning(getI18nBean().getText("admin.mail.unable.to.create.issue"), e);
		}

		return false;
	}

	private IssueCreationHelperBean getIssueCreationHelperBean() {
		return (IssueCreationHelperBean) ComponentAccessor.getComponent(IssueCreationHelperBean.class);
	}

	private PermissionManager getPermissionManager() {
		return ComponentAccessor.getPermissionManager();
	}

	private FieldVisibilityManager getFieldVisibilityManager() {
		return (FieldVisibilityManager) ComponentAccessor.getComponent(FieldVisibilityManager.class);
	}

	private AssigneeResolver getAssigneeResolver() {
		return (AssigneeResolver) ComponentAccessor.getComponent(AssigneeResolver.class);
	}

	private IssueManager getIssueManager() {
		return ComponentAccessor.getIssueManager();
	}

	private IssueFactory getIssueFactory() {
		return ComponentAccessor.getIssueFactory();
	}

	private String getDescription(User reporter, Message message) throws MessagingException {
		return recordFromAddressForAnon(reporter, message, MailUtils.getBody(message));
	}

	private String getPriority(Message message) throws MessagingException {
		String[] xPrioHeaders = message.getHeader("X-Priority");

		if ((xPrioHeaders != null) && (xPrioHeaders.length > 0)) {
			String xPrioHeader = xPrioHeaders[0];

			int priorityValue = Integer.parseInt(TextUtils.extractNumber(xPrioHeader));

			if (priorityValue == 0) {
				return getDefaultSystemPriority();
			}

			Collection priorities = getConstantsManager().getPriorityObjects();

			Iterator priorityIt = priorities.iterator();

			int priorityNumber = (int) Math.ceil(priorityValue / 5.0D * priorities.size());

			if (priorityNumber > priorities.size()) {
				priorityNumber = priorities.size();
			}

			String priority = null;

			for (int i = 0; i < priorityNumber; i++) {
				priority = ((Priority) priorityIt.next()).getId();
			}

			return priority;
		}

		return getDefaultSystemPriority();
	}

	private String getDefaultSystemPriority() {
		Priority defaultPriority = getConstantsManager().getDefaultPriorityObject();
		if (defaultPriority == null) {
			this.log.warn("Default priority was null. Using the 'middle' priority.");
			Collection priorities = getConstantsManager().getPriorityObjects();
			int times = (int) Math.ceil(priorities.size() / 2.0D);
			Iterator priorityIt = priorities.iterator();
			for (int i = 0; i < times; i++) {
				defaultPriority = (Priority) priorityIt.next();
			}
		}
		if (defaultPriority == null) {
			throw new RuntimeException("Default priority not found");
		}
		return defaultPriority.getId();
	}

	private void setDefaultSecurityLevel(MutableIssue issue) throws Exception {
		GenericValue project = issue.getProject();
		if (project != null) {
			Long levelId = getIssueSecurityLevelManager().getSchemeDefaultSecurityLevel(project);
			if (levelId != null) {
				issue.setSecurityLevel(getIssueSecurityLevelManager().getIssueSecurity(levelId));
			}
		}
	}

	private IssueSecurityLevelManager getIssueSecurityLevelManager() {
		return (IssueSecurityLevelManager) ComponentManager.getComponentInstanceOfType(IssueSecurityLevelManager.class);
	}

	private ConstantsManager getConstantsManager() {
		return (ConstantsManager) ComponentManager.getComponentInstanceOfType(ConstantsManager.class);
	}

	private String recordFromAddressForAnon(User reporter, Message message, String description) throws MessagingException {
		if ((this.reporteruserName != null) && (this.reporteruserName.equals(reporter.getName()))) {
			description = description + "\n[Created via e-mail ";
			if ((message.getFrom() != null) && (message.getFrom().length > 0)) {
				description = description + "received from: " + message.getFrom()[0] + "]";
			} else {
				description = description + "but could not establish sender's address.]";
			}
		} else {
			Address[] senders = message.getFrom();
			StringBuilder sb = new StringBuilder();
			sb.append("Sender: ");
			for (Address sender : senders) {
				sb.append(sender.toString());
				sb.append(" ");
			}
			sb.append("\n----\n");
			sb.append(description);
			description = sb.toString();
		}
		return description;
	}
}
