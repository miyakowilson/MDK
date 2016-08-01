package gov.nasa.jpl.mbee.ems.sync.delta;

import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.Project;
import com.nomagic.magicdraw.core.ProjectUtilities;
import com.nomagic.magicdraw.openapi.uml.ModelElementsManager;
import com.nomagic.magicdraw.openapi.uml.ReadOnlyElementException;
import com.nomagic.magicdraw.openapi.uml.SessionManager;
import com.nomagic.magicdraw.teamwork.application.TeamworkUtils;
import com.nomagic.task.ProgressStatus;
import com.nomagic.task.RunnableWithProgress;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import gov.nasa.jpl.mbee.DocGenPlugin;
import gov.nasa.jpl.mbee.ems.ExportUtility;
import gov.nasa.jpl.mbee.ems.ImportException;
import gov.nasa.jpl.mbee.ems.ImportUtility;
import gov.nasa.jpl.mbee.ems.ServerException;
import gov.nasa.jpl.mbee.ems.jms.JMSUtils;
import gov.nasa.jpl.mbee.ems.sync.local.LocalSyncTransactionCommitListener;
import gov.nasa.jpl.mbee.ems.sync.queue.Request;
import gov.nasa.jpl.mbee.ems.sync.local.LocalSyncProjectEventListenerAdapter;
import gov.nasa.jpl.mbee.ems.sync.jms.JMSMessageListener;
import gov.nasa.jpl.mbee.ems.sync.jms.JMSSyncProjectEventListenerAdapter;
import gov.nasa.jpl.mbee.ems.sync.queue.OutputQueue;
import gov.nasa.jpl.mbee.ems.validation.ModelValidator;
import gov.nasa.jpl.mbee.ems.validation.actions.DetailDiff;
import gov.nasa.jpl.mbee.lib.Changelog;
import gov.nasa.jpl.mbee.lib.MDUtils;
import gov.nasa.jpl.mbee.lib.Pair;
import gov.nasa.jpl.mbee.lib.Utils;
import gov.nasa.jpl.mbee.lib.function.BiFunction;
import gov.nasa.jpl.mbee.lib.function.BiPredicate;
import gov.nasa.jpl.mbee.viewedit.ViewEditUtils;
import gov.nasa.jpl.mgss.mbee.docgen.validation.ValidationRule;
import gov.nasa.jpl.mgss.mbee.docgen.validation.ValidationRuleViolation;
import gov.nasa.jpl.mgss.mbee.docgen.validation.ValidationSuite;
import gov.nasa.jpl.mgss.mbee.docgen.validation.ViolationSeverity;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.TextMessage;
import java.util.*;

public class DeltaSyncRunner implements RunnableWithProgress {
    private final boolean shouldCommitDeletes, shouldCommit, shouldUpdate;

    private final Project project = Application.getInstance().getProject();

    private boolean failure = false;

    private ValidationSuite changelogSuite = new ValidationSuite("Updated Elements/Failed Updates");
    private ValidationRule locallyChangedValidationRule = new ValidationRule("updated", "updated", ViolationSeverity.INFO);
    private ValidationRule cannotUpdate = new ValidationRule("cannotUpdate", "cannotUpdate", ViolationSeverity.ERROR);
    private ValidationRule cannotRemove = new ValidationRule("cannotDelete", "cannotDelete", ViolationSeverity.WARNING);
    private ValidationRule cannotCreate = new ValidationRule("cannotCreate", "cannotCreate", ViolationSeverity.ERROR);

    private Changelog<String, Element> failedLocalChangelog = new Changelog<>();
    private Changelog<String, Void> failedJmsChangelog = new Changelog<>();

    private List<ValidationSuite> vss = new ArrayList<>();

    {
        changelogSuite.addValidationRule(locallyChangedValidationRule);
        changelogSuite.addValidationRule(cannotUpdate);
        changelogSuite.addValidationRule(cannotRemove);
        changelogSuite.addValidationRule(cannotCreate);
    }

    /*public DeltaSyncRunner(boolean shouldCommit, boolean skipUpdate, boolean shouldDelete) {
        this.shouldCommit = shouldCommit;
        this.skipUpdate = skipUpdate;
        this.shouldDelete = shouldDelete;
    }*/

    public DeltaSyncRunner(boolean shouldCommit, boolean shouldCommitDeletes) {
        this(shouldCommit, shouldCommitDeletes, true);
    }

    public DeltaSyncRunner(boolean shouldCommmit, boolean shouldCommitDeletes, boolean shouldUpdate) {
        this.shouldCommit = shouldCommmit;
        this.shouldCommitDeletes = shouldCommitDeletes;
        this.shouldUpdate = shouldUpdate;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void run(ProgressStatus ps) {
        if (ProjectUtilities.isFromTeamworkServer(project.getPrimaryProject()) && TeamworkUtils.getLoggedUserName() == null) {
            failure = true;
            Utils.guilog("[ERROR] You need to be logged in to Teamwork first.");
            return;
        }

        LocalSyncTransactionCommitListener listener = LocalSyncProjectEventListenerAdapter.getProjectMapping(project).getLocalSyncTransactionCommitListener();
        if (listener == null) {
            Utils.guilog("[ERROR] Unexpected error occurred. Cannot get commit listener.");
            failure = true;
            return;
        }

        // LOCK SYNC FOLDER

        listener.setDisabled(true);
        SyncElements.lockSyncFolder(project);
        listener.setDisabled(false);

        // DOWNLOAD JMS MESSAGES IF ASYNC CONSUMER IS DISABLED

        JMSSyncProjectEventListenerAdapter.JMSSyncProjectMapping jmsSyncProjectMapping = JMSSyncProjectEventListenerAdapter.getProjectMapping(Application.getInstance().getProject());
        JMSMessageListener jmsMessageListener = jmsSyncProjectMapping.getJmsMessageListener();
        if (jmsMessageListener == null) {
            Application.getInstance().getGUILog().log("[ERROR] Could not connect to MMS. Please verify your url/site configuration.");
            failure = true;
            return;
        }
        if (jmsSyncProjectMapping.isDisabled()) {
            jmsSyncProjectMapping.setDisabled(!JMSSyncProjectEventListenerAdapter.initDurable(project, jmsSyncProjectMapping));
            /*List<TextMessage> textMessages = jmsSyncProjectMapping.getAllTextMessages(true);
            if (textMessages == null) {
                Utils.guilog("[ERROR] Could not get changes from MMS. Please check your network connection and try again.");
                failure = true;
                return;
            }
            for (TextMessage textMessage : textMessages) {
                jmsMessageListener.onMessage(textMessage);
            }*/
        }

        // BUILD COMPLETE LOCAL CHANGELOG

        Changelog<String, Element> persistedLocalChangelog = new Changelog<>();
        //JSONObject persistedLocalChanges = DeltaSyncProjectEventListenerAdapter.getUpdatesOrFailed(Application.getInstance().getProject(), "update");
        Collection<SyncElement> persistedLocalSyncElements = SyncElements.getAllOfType(project, SyncElement.Type.UPDATE);
        for (SyncElement syncElement : persistedLocalSyncElements) {
            persistedLocalChangelog = persistedLocalChangelog.and(SyncElements.buildChangelog(syncElement), new BiFunction<String, Void, Element>() {
                @Override
                public Element apply(String key, Void value) {
                    return ExportUtility.getElementFromID(key);
                }
            });
        }
        Changelog<String, Element> localChangelog = persistedLocalChangelog.and(listener.getInMemoryLocalChangelog());


        Map<String, Element> localCreated = localChangelog.get(Changelog.ChangeType.CREATED),
                localUpdated = localChangelog.get(Changelog.ChangeType.UPDATED),
                localDeleted = localChangelog.get(Changelog.ChangeType.DELETED);

        // BUILD COMPLETE JMS CHANGELOG

        Changelog<String, Void> persistedJmsChangelog = new Changelog<>();
        Collection<SyncElement> persistedJmsSyncElements = SyncElements.getAllOfType(project, SyncElement.Type.JMS);
        //JSONObject persistedJmsChanges = DeltaSyncProjectEventListenerAdapter.getUpdatesOrFailed(Application.getInstance().getProject(), "jms");
        for (SyncElement syncElement : persistedJmsSyncElements) {
            persistedJmsChangelog = persistedJmsChangelog.and(SyncElements.buildChangelog(syncElement));
        }
        Changelog<String, Void> jmsChangelog = persistedJmsChangelog.and(jmsMessageListener.getInMemoryJMSChangelog(), new BiFunction<String, JSONObject, Void>() {
            @Override
            public Void apply(String key, JSONObject jsonObject) {
                return null;
            }
        });

        Map<String, Void> jmsCreated = jmsChangelog.get(Changelog.ChangeType.CREATED),
                jmsUpdated = jmsChangelog.get(Changelog.ChangeType.UPDATED),
                jmsDeleted = jmsChangelog.get(Changelog.ChangeType.DELETED);

        Set<String> elementIdsToGet = new HashSet<>(jmsUpdated.keySet());
        elementIdsToGet.addAll(jmsCreated.keySet());
        if (shouldUpdate && !jmsChangelog.isEmpty()) {
            int size = jmsChangelog.flattenedSize();
            Application.getInstance().getGUILog().log("[INFO] Getting " + size + " changed element" + (size != 1 ? "s" : "") + " from the MMS.");
        }

        Map<String, JSONObject> jmsJsons = new HashMap<>(elementIdsToGet.size());

        // Get latest json for element added/changed from MMS

        if (!elementIdsToGet.isEmpty()) {
            JSONObject getJson = new JSONObject();
            JSONArray getElements = new JSONArray();
            getJson.put("elements", getElements);
            for (String e : elementIdsToGet) {
                JSONObject el = new JSONObject();
                el.put("sysmlid", e);
                getElements.add(el);
            }
            String url = ExportUtility.getUrlWithWorkspace();
            url += "/elements";
            String response = null;
            try {
                response = ExportUtility.getWithBody(url, getJson.toJSONString());
            } catch (ServerException ex) {
                Utils.guilog("[ERROR] Get elements failed.");
            }
            if (response == null) {
                failure = true;
                Utils.guilog("[ERROR] Cannot get elements from MMS server. Update aborted. All changes will be attempted at next update.");
                return;
            }
            JSONObject webObject = (JSONObject) JSONValue.parse(response);
            JSONArray webArray = (JSONArray) webObject.get("elements");
            for (Object o : webArray) {
                String webId = (String) ((JSONObject) o).get("sysmlid");
                jmsJsons.put(webId, (JSONObject) o);
            }
        }

        // NEW CONFLICT DETECTION

        Map<String, Pair<Changelog.Change<Element>, Changelog.Change<Void>>> conflictedChanges = new LinkedHashMap<>(),
                unconflictedChanges = new LinkedHashMap<>();
        localChangelog.findConflicts(jmsChangelog, new BiPredicate<Changelog.Change<Element>, Changelog.Change<Void>>() {
            @Override
            public boolean test(Changelog.Change<Element> change, Changelog.Change<Void> change2) {
                return change != null && change2 != null;
            }
        }, conflictedChanges, unconflictedChanges);

        // MAP CHANGES TO ACTIONABLE GROUPS

        Map<String, Element> localElementsToPost = new LinkedHashMap<>(localCreated.size() + localUpdated.size());
        Set<String> localElementsToDelete = new HashSet<>(localDeleted.size());

        Map<String, JSONObject> jmsElementsToCreateLocally = new LinkedHashMap<>(jmsCreated.size());
        Map<String, Pair<JSONObject, Element>> jmsElementsToUpdateLocally = new LinkedHashMap<>(jmsUpdated.size());
        Map<String, Element> jmsElementsToDeleteLocally = new LinkedHashMap<>(jmsDeleted.size());

        // only one side of the pair will have a value when unconflicted
        for (Map.Entry<String, Pair<Changelog.Change<Element>, Changelog.Change<Void>>> unconflictedEntry : unconflictedChanges.entrySet()) {
            String id = unconflictedEntry.getKey();
            Changelog.Change<Element> localChange = unconflictedEntry.getValue().getFirst();
            Changelog.Change<JSONObject> jmsChange = unconflictedEntry.getValue().getSecond() != null ?
                    new Changelog.Change<>(jmsJsons.get(id), unconflictedEntry.getValue().getSecond().getType()) : null;

            if (shouldCommit && localChange != null) {
                Element element = localChange.getChanged();
                switch (localChange.getType()) {
                    case CREATED:
                    case UPDATED:
                        if (element == null) {
                            Application.getInstance().getGUILog().log("[INFO] Attempted to create/update element " + id + " on the MMS, but it no longer exists locally. Skipping.");
                            continue;
                        }
                        localElementsToPost.put(id, element);
                        break;
                    case DELETED:
                        if (element != null && !element.isInvalid()) {
                            Application.getInstance().getGUILog().log("[INFO] Attempted to delete element " + id + " from the MMS, but it still exists locally. Skipping.");
                            continue;
                        }
                        localElementsToDelete.add(id);
                        break;
                }
            }
            else if (shouldUpdate && jmsChange != null) {
                JSONObject json = jmsChange.getChanged();
                Element element = ExportUtility.getElementFromID(id);
                switch (jmsChange.getType()) {
                    case CREATED:
                        if (json == null) {
                            Application.getInstance().getGUILog().log("[INFO] Attempted to create element " + id + " locally, but it no longer exists on the MMS. Skipping.");
                            continue;
                        }
                        if (element != null) {
                            Application.getInstance().getGUILog().log("[INFO] Attempted to create element " + id + " locally, but it already exists. Skipping.");
                            continue;
                        }
                        jmsElementsToCreateLocally.put(id, json);
                        break;
                    case UPDATED:
                        if (json == null) {
                            Application.getInstance().getGUILog().log("[INFO] Attempted to update element " + id + " locally, but it no longer exists on the MMS. Skipping.");
                            continue;
                        }
                        if (element == null) {
                            Application.getInstance().getGUILog().log("[INFO] Attempted to update element " + id + " locally, but it does not exist. Skipping.");
                            continue;
                        }
                        if (!element.isEditable()) {
                            if (MDUtils.isDeveloperMode()) {
                                Application.getInstance().getGUILog().log("[INFO] Attempted to update element " + id + " locally, but it is not editable. Skipping.");
                            }
                            failedJmsChangelog.addChange(id, null, Changelog.ChangeType.UPDATED);
                            continue;
                        }
                        jmsElementsToUpdateLocally.put(id, new Pair<>(json, element));
                        break;
                    case DELETED:
                        if (element == null) {
                            Application.getInstance().getGUILog().log("[INFO] Attempted to delete element " + id + " locally, but it doesn't exist. Skipping.");
                            continue;
                        }
                        if (!element.isEditable()) {
                            if (MDUtils.isDeveloperMode()) {
                                Application.getInstance().getGUILog().log("[INFO] Attempted to delete element " + id + " locally, but it is not editable. Skipping.");
                            }
                            failedJmsChangelog.addChange(id, null, Changelog.ChangeType.DELETED);
                            continue;
                        }
                        jmsElementsToDeleteLocally.put(id, element);
                        break;
                }
            }
        }

        // COMMIT UNCONFLICTED CREATIONS AND UPDATES TO MMS

        boolean shouldLogNoLocalChanges = shouldCommit;
        if (shouldCommit && !localElementsToPost.isEmpty()) {
            JSONArray elementsJsonArray = new JSONArray();
            for (Element element : localElementsToPost.values()) {
                JSONObject elementJsonObject = ExportUtility.fillElement(element, null);
                if (elementJsonObject != null) {
                    elementsJsonArray.add(elementJsonObject);
                }
            }
            if (!elementsJsonArray.isEmpty()) {
                JSONObject body = new JSONObject();
                body.put("elements", elementsJsonArray);
                body.put("source", "magicdraw");
                body.put("mmsVersion", DocGenPlugin.VERSION);
                Application.getInstance().getGUILog().log("[INFO] Queueing request to create/update " + elementsJsonArray.size() + " local element" + (elementsJsonArray.size() != 1 ? "s" : "") + " on the MMS.");
                OutputQueue.getInstance().offer(new Request(ExportUtility.getPostElementsUrl(), body.toJSONString(), "POST", true, elementsJsonArray.size(), "Sync Changes"));
                shouldLogNoLocalChanges = false;
            }
        }

        // COMMIT UNCONFLICTED DELETIONS TO MMS

        if (shouldCommit && shouldCommitDeletes && !localElementsToDelete.isEmpty()) {
            Application.getInstance().getGUILog().log("[INFO] Adding local deletions to MMS request queue.");
            JSONArray elementsJsonArray = new JSONArray();
            for (String id : localElementsToDelete) {
                JSONObject elementJsonObject = new JSONObject();
                elementJsonObject.put("sysmlid", id);
                elementsJsonArray.add(elementJsonObject);
            }
            JSONObject body = new JSONObject();
            body.put("elements", elementsJsonArray);
            body.put("source", "magicdraw");
            body.put("mmsVersion", DocGenPlugin.VERSION);
            Application.getInstance().getGUILog().log("[INFO] Queuing request to delete " + elementsJsonArray.size() + " local element" + (elementsJsonArray.size() != 1 ? "s" : "") + " on the MMS.");
            gov.nasa.jpl.mbee.ems.sync.queue.OutputQueue.getInstance().offer(new Request(ExportUtility.getUrlWithWorkspace() + "/elements", body.toJSONString(), "DELETEALL", true, elementsJsonArray.size(), "Sync Deletes"));
            shouldLogNoLocalChanges = false;
        }

        // OUTPUT RESULT OF LOCAL CHANGES

        if (shouldLogNoLocalChanges) {
            Application.getInstance().getGUILog().log("[INFO] No local changes to commit to MMS.");
        }

        // PREP UPDATED (FROM JMS) ELEMENTS CHANGELOG

        Changelog<String, Void> successfulJmsChangelog = new Changelog<>();

        // ADD CREATED ELEMENTS LOCALLY FROM MMS

        boolean shouldLogNoJmsChanges = shouldUpdate;
        if (shouldUpdate && !jmsElementsToCreateLocally.isEmpty()) {
            ImportUtility.CreationOrder creationOrder = ImportUtility.getCreationOrder(jmsElementsToCreateLocally.values());
            List<JSONObject> sortedJmsElementsToCreateLocally = creationOrder.getOrder();
            if (!sortedJmsElementsToCreateLocally.isEmpty()) {
                listener.setDisabled(true);
                if (!SessionManager.getInstance().isSessionCreated()) {
                    SessionManager.getInstance().createSession("DeltaSyncRunner execution");
                }
                ImportUtility.setShouldOutputError(false);
                List<JSONObject> createdElementJsons = new ArrayList<>(sortedJmsElementsToCreateLocally.size());
                for (JSONObject elementJson : sortedJmsElementsToCreateLocally) {
                    try {
                        Element element = ImportUtility.createElement(elementJson, false);
                        if (element != null) {
                            createdElementJsons.add(elementJson);
                        }
                    } catch (ImportException ie) {
                        ie.printStackTrace();
                        failedJmsChangelog.addChange((String) elementJson.get("sysmlid"), null, Changelog.ChangeType.CREATED);
                        ValidationRuleViolation vrv = new ValidationRuleViolation(null, "[CREATE FAILED] " + ie.getMessage());
                        vrv.addAction(new DetailDiff(new JSONObject(), elementJson));
                        cannotCreate.addViolation(vrv);
                    }
                }
                Map<String, Void> createdElements = successfulJmsChangelog.get(Changelog.ChangeType.CREATED);
                for (JSONObject elementJson : createdElementJsons) {
                    try {
                        Element element = ImportUtility.createElement(elementJson, true);
                        createdElements.put(ExportUtility.getElementID(element), null);
                        locallyChangedValidationRule.addViolation(new ValidationRuleViolation(element, "[CREATED]"));
                    } catch (ImportException ie) {
                        ie.printStackTrace();
                        failedJmsChangelog.addChange((String) elementJson.get("sysmlid"), null, Changelog.ChangeType.CREATED);
                        ValidationRuleViolation vrv = new ValidationRuleViolation(null, "[CREATE FAILED] " + ie.getMessage());
                        vrv.addAction(new DetailDiff(new JSONObject(), elementJson));
                        cannotCreate.addViolation(vrv);
                    }
                }
                listener.setDisabled(false);
                ImportUtility.setShouldOutputError(true);
                if (!createdElements.isEmpty()) {
                    shouldLogNoJmsChanges = false;
                    Application.getInstance().getGUILog().log("[INFO] Added " + createdElements.size() + " element" + (createdElements.size() != 1 ? "s" : "") + " locally from the MMS.");
                }
                for (JSONObject element : creationOrder.getFailed()) {
                    failedJmsChangelog.addChange((String) element.get("sysmlid"), null, Changelog.ChangeType.CREATED);
                    ValidationRuleViolation vrv = new ValidationRuleViolation(null, "[CREATE FAILED] Owner or chain of owners not found");
                    vrv.addAction(new DetailDiff(new JSONObject(), element));
                    cannotCreate.addViolation(vrv);
                }
            }
        }

        // CHANGE UPDATED ELEMENTS LOCALLY FROM MMS

        if (shouldUpdate && !jmsElementsToUpdateLocally.isEmpty()) {
            listener.setDisabled(true);
            if (!SessionManager.getInstance().isSessionCreated()) {
                SessionManager.getInstance().createSession("DeltaSyncRunner execution");
            }
            Map<String, Void> updatedElements = successfulJmsChangelog.get(Changelog.ChangeType.UPDATED);
            for (Map.Entry<String, Pair<JSONObject, Element>> elementEntry : jmsElementsToUpdateLocally.entrySet()) {
                Element element = elementEntry.getValue().getSecond();
                JSONObject elementJson = elementEntry.getValue().getFirst();
                // Element both exists and is editable here
                try {
                    ImportUtility.updateElement(element, elementJson);
                    if (!(element.getOwner() != null && elementJson.get("qualifiedId") instanceof String && ((String) elementJson.get("qualifiedId")).contains("/holding_bin/"))) {
                        ImportUtility.setOwner(element, elementJson);
                    }
                    updatedElements.put(ExportUtility.getElementID(element), null);
                    locallyChangedValidationRule.addViolation(new ValidationRuleViolation(element, "[UPDATED]"));
                } catch (ImportException ie) {
                    ie.printStackTrace();
                    ValidationRuleViolation vrv = new ValidationRuleViolation(element, "[UPDATE FAILED] " + ie.getMessage());
                    cannotUpdate.addViolation(vrv);
                    failedJmsChangelog.addChange(elementEntry.getKey(), null, Changelog.ChangeType.UPDATED);
                }
            }
            listener.setDisabled(false);
            if (!updatedElements.isEmpty()) {
                shouldLogNoJmsChanges = false;
                Application.getInstance().getGUILog().log("[INFO] Updated " + updatedElements.size() + " element" + (updatedElements.size() != 1 ? "s" : "") + " locally from the MMS.");
            }
        }

        // REMOVE DELETED ELEMENTS LOCALLY FROM MMS

        if (shouldUpdate && !jmsElementsToDeleteLocally.isEmpty()) {
            listener.setDisabled(true);
            if (!SessionManager.getInstance().isSessionCreated()) {
                SessionManager.getInstance().createSession(getClass().getName() + " Execution");
            }
            Map<String, Void> deletedElements = successfulJmsChangelog.get(Changelog.ChangeType.DELETED);
            for (Map.Entry<String, Element> elementEntry : jmsElementsToDeleteLocally.entrySet()) {
                Element element = elementEntry.getValue();
                try {
                    ModelElementsManager.getInstance().removeElement(element);
                    deletedElements.put(elementEntry.getKey(), null);
                    locallyChangedValidationRule.addViolation(new ValidationRuleViolation(element, "[DELETED]"));
                } catch (ReadOnlyElementException roee) {
                    roee.printStackTrace();
                    ValidationRuleViolation vrv = new ValidationRuleViolation(element, "[DELETE FAILED] " + roee.getMessage());
                    cannotUpdate.addViolation(vrv);
                    failedJmsChangelog.addChange(elementEntry.getKey(), null, Changelog.ChangeType.DELETED);
                }
            }
            listener.setDisabled(false);
            if (!deletedElements.isEmpty()) {
                shouldLogNoJmsChanges = true;
                Application.getInstance().getGUILog().log("[INFO] Deleted " + deletedElements.size() + " element" + (deletedElements.size() != 1 ? "s" : "") + " locally from the MMS.");
            }
        }

        // OUTPUT RESULT OF JMS CHANGES

        if (shouldLogNoJmsChanges) {
            Application.getInstance().getGUILog().log("[INFO] No MMS changes to update locally.");
        }

        // SHOW VALIDATION WINDOW OF UNCONFLICTED CHANGES

        vss.add(changelogSuite);
        if (changelogSuite.hasErrors()) {
            Utils.displayValidationWindow(vss, "Delta Sync Update Changelog");
        }

        // CLOSE SESSION IF OPENED

        if (SessionManager.getInstance().isSessionCreated()) {
            SessionManager.getInstance().closeSession();
        }

        // HANDLE CONFLICTS

        Set<Element> localConflictedElements = new HashSet<>();
        JSONObject body = new JSONObject();
        JSONArray elementsJsonArray = new JSONArray();
        body.put("elements", elementsJsonArray);

        for (Map.Entry<String, Pair<Changelog.Change<Element>, Changelog.Change<Void>>> conflictedEntry : conflictedChanges.entrySet()) {
            String id = conflictedEntry.getKey();
            Changelog.Change<Element> localChange = conflictedEntry.getValue().getFirst();
            Changelog.Change<JSONObject> jmsChange = conflictedEntry.getValue().getSecond() != null ?
                    new Changelog.Change<>(jmsJsons.get(id), conflictedEntry.getValue().getSecond().getType()) : null;

            if (localChange != null && localChange.getChanged() != null) {
                localConflictedElements.add(localChange.getChanged());
            }
            if (jmsChange != null && jmsChange.getChanged() != null) {
                elementsJsonArray.add(jmsChange.getChanged());
            }
        }

        ModelValidator modelValidator = new ModelValidator(null, body, false, localConflictedElements, false);
        try {
            modelValidator.validate(false, null);
        } catch (ServerException ignored) {

        }
        if (!modelValidator.getDifferentElementIDs().isEmpty()) {
            Application.getInstance().getGUILog().log("[INFO] There are potential conflicts in " + modelValidator.getDifferentElementIDs().size() + " element" + (modelValidator.getDifferentElementIDs().size() != 1 ? "s" : "") + " between MMS and local changes. Please resolve them and re-sync.");
            vss.add(modelValidator.getSuite());
            modelValidator.showWindow();

            for (Map.Entry<String, Pair<Changelog.Change<Element>, Changelog.Change<Void>>> conflictedEntry : conflictedChanges.entrySet()) {
                String id = conflictedEntry.getKey();
                if (!modelValidator.getDifferentElementIDs().contains(id)) {
                    continue;
                }
                Changelog.Change<Element> localChange = conflictedEntry.getValue().getFirst();
                Changelog.Change<JSONObject> jmsChange = conflictedEntry.getValue().getSecond() != null ?
                        new Changelog.Change<>(jmsJsons.get(id), conflictedEntry.getValue().getSecond().getType()) : null;

                if (localChange != null && localChange.getChanged() != null) {
                    failedLocalChangelog.addChange(conflictedEntry.getKey(), localChange.getChanged(), localChange.getType());
                }
                if (jmsChange != null && jmsChange.getChanged() != null) {
                    failedJmsChangelog.addChange(conflictedEntry.getKey(), null, jmsChange.getType());
                }
            }
        }

        // CLEAR IN-MEMORY AND PERSIST UNPROCESSED & FAILURES

        listener.getInMemoryLocalChangelog().clear();
        jmsMessageListener.getInMemoryJMSChangelog().clear();

        listener.setDisabled(true);
        if (!SessionManager.getInstance().isSessionCreated()) {
            SessionManager.getInstance().createSession("Delta Sync Changelog Persistence");
        }

        Changelog<String, Void> unprocessedLocalChangelog = new Changelog<>();
        if (!shouldCommit) {
            unprocessedLocalChangelog = unprocessedLocalChangelog.and(localChangelog, new BiFunction<String, Element, Void>() {
                @Override
                public Void apply(String s, Element element) {
                    return null;
                }
            });
        }
        if (shouldCommit && !shouldCommitDeletes) {
            Map<String, Void> unprocessedLocalDeletedChanges = unprocessedLocalChangelog.get(Changelog.ChangeType.DELETED);
            for (String key : localChangelog.get(Changelog.ChangeType.DELETED).keySet()) {
                unprocessedLocalDeletedChanges.put(key, null);
            }
        }
        unprocessedLocalChangelog = unprocessedLocalChangelog.and(failedLocalChangelog, new BiFunction<String, Element, Void>() {
            @Override
            public Void apply(String s, Element element) {
                return null;
            }
        });
        SyncElements.setByType(project, SyncElement.Type.UPDATE, SyncElements.buildJson(unprocessedLocalChangelog).toJSONString());

        Changelog<String, Void> unprocessedJmsChangelog = new Changelog<>();
        if (!shouldUpdate) {
            unprocessedJmsChangelog = unprocessedJmsChangelog.and(jmsChangelog);
        }
        unprocessedJmsChangelog = unprocessedJmsChangelog.and(failedJmsChangelog);
        SyncElements.setByType(project, SyncElement.Type.JMS, SyncElements.buildJson(unprocessedJmsChangelog).toJSONString());

        SessionManager.getInstance().closeSession();
        listener.setDisabled(false);

        // ACKNOWLEDGE LAST JMS MESSAGE TO CLEAR OWN QUEUE

        Message lastMessage = jmsMessageListener.getLastMessage();
        if (lastMessage != null) {
            try {
                lastMessage.acknowledge();
            } catch (JMSException e) {
                e.printStackTrace();
            }
        }

        // NOTIFY OTHER USERS OF PROCESSED ELEMENTS

        if (!successfulJmsChangelog.isEmpty()) {
            JSONObject teamworkCommittedMessage = new JSONObject();
            teamworkCommittedMessage.put("source", "magicdraw");
            teamworkCommittedMessage.put("sender", ViewEditUtils.getUsername());
            teamworkCommittedMessage.put("synced", SyncElements.buildJson(successfulJmsChangelog));
            try {
                TextMessage successfulTextMessage = jmsSyncProjectMapping.getSession().createTextMessage(teamworkCommittedMessage.toJSONString());
                successfulTextMessage.setStringProperty(JMSUtils.MSG_SELECTOR_PROJECT_ID, ExportUtility.getProjectId(project));
                successfulTextMessage.setStringProperty(JMSUtils.MSG_SELECTOR_WORKSPACE_ID, ExportUtility.getWorkspace() + "_mdk");
                jmsSyncProjectMapping.getMessageProducer().send(successfulTextMessage);
                int syncCount = successfulJmsChangelog.flattenedSize();
                Application.getInstance().getGUILog().log("[INFO] Notified other clients of " + syncCount + " locally updated element" + (syncCount != 1 ? "s" : "") + ".");
            } catch (JMSException e) {
                e.printStackTrace();
                Application.getInstance().getGUILog().log("[ERROR] Failed to notify other clients of synced elements. This could result in redundant local updates.");
            }
        }
    }

    public boolean isFailure() {
        return failure;
    }

    public List<ValidationSuite> getValidations() {
        return vss;
    }

}