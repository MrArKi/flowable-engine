/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.flowable.task.service.impl.persistence.entity;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.impl.history.HistoryLevel;
import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.common.engine.impl.persistence.entity.data.DataManager;
import org.flowable.identitylink.service.IdentityLinkService;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskBuilder;
import org.flowable.task.api.TaskInfo;
import org.flowable.task.api.history.HistoricTaskLogEntryType;
import org.flowable.task.service.TaskServiceConfiguration;
import org.flowable.task.service.event.impl.FlowableTaskEventBuilder;
import org.flowable.task.service.impl.TaskQueryImpl;
import org.flowable.task.service.impl.persistence.CountingTaskEntity;
import org.flowable.task.service.impl.persistence.entity.data.TaskDataManager;
import org.flowable.task.service.impl.util.CommandContextUtil;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * @author Tom Baeyens
 * @author Joram Barrez
 */
public class TaskEntityManagerImpl extends AbstractEntityManager<TaskEntity> implements TaskEntityManager {

    protected TaskDataManager taskDataManager;

    public TaskEntityManagerImpl(TaskServiceConfiguration taskServiceConfiguration, TaskDataManager taskDataManager) {
        super(taskServiceConfiguration);
        this.taskDataManager = taskDataManager;
    }

    @Override
    protected DataManager<TaskEntity> getDataManager() {
        return taskDataManager;
    }

    @Override
    public TaskEntity create() {
        TaskEntity taskEntity = super.create();
        taskEntity.setCreateTime(getClock().getCurrentTime());
        if (taskServiceConfiguration.isEnableTaskRelationshipCounts()) {
            ((CountingTaskEntity) taskEntity).setCountEnabled(true);
        }
        return taskEntity;
    }

    @Override
    public TaskEntity createTask(TaskBuilder taskBuilder) {
        // create and insert task
        TaskEntity taskEntity = create();
        taskEntity.setId(taskBuilder.getId());
        taskEntity.setName(taskBuilder.getName());
        taskEntity.setDescription(taskBuilder.getDescription());
        taskEntity.setPriority(taskBuilder.getPriority());
        taskEntity.setOwner(taskBuilder.getOwner());
        taskEntity.setAssignee(taskBuilder.getAssignee());
        taskEntity.setDueDate(taskBuilder.getDueDate());
        taskEntity.setCategory(taskBuilder.getCategory());
        taskEntity.setParentTaskId(taskBuilder.getParentTaskId());
        taskEntity.setTenantId(taskBuilder.getTenantId());
        taskEntity.setFormKey(taskBuilder.getFormKey());
        taskEntity.setTaskDefinitionId(taskBuilder.getTaskDefinitionId());
        taskEntity.setTaskDefinitionKey(taskBuilder.getTaskDefinitionKey());
        taskEntity.setScopeId(taskBuilder.getScopeId());
        taskEntity.setScopeType(taskBuilder.getScopeType());
        insert(taskEntity);

        TaskEntity enrichedTaskEntity = this.taskServiceConfiguration.getTaskPostProcessor().enrich(taskEntity);
        update(enrichedTaskEntity, false);
        taskBuilder.getIdentityLinks().forEach(
                identityLink -> {
                    if (identityLink.getGroupId() != null) {
                        enrichedTaskEntity.addGroupIdentityLink(identityLink.getGroupId(), identityLink.getType());
                    } else if (identityLink.getUserId() != null) {
                        enrichedTaskEntity.addUserIdentityLink(identityLink.getUserId(), identityLink.getType());
                    }
                }
        );

        if (getEventDispatcher() != null && getEventDispatcher().isEnabled() && taskEntity.getAssignee() != null) {
            getEventDispatcher().dispatchEvent(
                    FlowableTaskEventBuilder.createEntityEvent(FlowableEngineEventType.TASK_ASSIGNED, taskEntity));
        }

        if (taskServiceConfiguration.getHistoryLevel().isAtLeast(HistoryLevel.AUDIT)) {
            taskServiceConfiguration.getHistoricTaskService().recordTaskCreated(taskEntity);
        }

        return enrichedTaskEntity;
    }

    @Override
    public void insert(TaskEntity taskEntity, boolean fireCreatedEvent) {
        super.insert(taskEntity, fireCreatedEvent);
        if (fireCreatedEvent) {
            logTaskCreatedEvent(taskEntity);
        }
    }

    @Override
    public TaskEntity update(TaskEntity taskEntity, boolean fireUpdateEvents) {
        if (fireUpdateEvents) {
            logTaskUpdateEvents(taskEntity);
        }
        return super.update(taskEntity, fireUpdateEvents);
    }

    protected IdentityLinkService getIdentityLinkService() {
        return CommandContextUtil.getIdentityLinkServiceConfiguration().getIdentityLinkService();
    }

    @Override
    public void changeTaskAssignee(TaskEntity taskEntity, String assignee) {
        if ((taskEntity.getAssignee() != null && !taskEntity.getAssignee().equals(assignee))
                || (taskEntity.getAssignee() == null && assignee != null)) {

            taskEntity.setAssignee(assignee);
            
            if (taskEntity.getId() != null) {
                getTaskServiceConfiguration().getInternalHistoryTaskManager().recordTaskInfoChange(taskEntity);
                update(taskEntity);
            }
        }
    }

    @Override
    public void changeTaskOwner(TaskEntity taskEntity, String owner) {
        if ((taskEntity.getOwner() != null && !taskEntity.getOwner().equals(owner))
                || (taskEntity.getOwner() == null && owner != null)) {
            
            taskEntity.setOwner(owner);

            if (taskEntity.getId() != null) {
                getTaskServiceConfiguration().getInternalHistoryTaskManager().recordTaskInfoChange(taskEntity);
                update(taskEntity);
            }
        }
    }

    @Override
    public List<TaskEntity> findTasksByExecutionId(String executionId) {
        return taskDataManager.findTasksByExecutionId(executionId);
    }

    @Override
    public List<TaskEntity> findTasksByProcessInstanceId(String processInstanceId) {
        return taskDataManager.findTasksByProcessInstanceId(processInstanceId);
    }
    
    @Override
    public List<TaskEntity> findTasksByScopeIdAndScopeType(String scopeId, String scopeType) {
        return taskDataManager.findTasksByScopeIdAndScopeType(scopeId, scopeType);
    }
    
    @Override
    public List<TaskEntity> findTasksBySubScopeIdAndScopeType(String subScopeId, String scopeType) {
        return taskDataManager.findTasksBySubScopeIdAndScopeType(subScopeId, scopeType);
    }

    @Override
    public List<Task> findTasksByQueryCriteria(TaskQueryImpl taskQuery) {
        return taskDataManager.findTasksByQueryCriteria(taskQuery);
    }

    @Override
    public List<Task> findTasksWithRelatedEntitiesByQueryCriteria(TaskQueryImpl taskQuery) {
        return taskDataManager.findTasksWithRelatedEntitiesByQueryCriteria(taskQuery);
    }

    @Override
    public long findTaskCountByQueryCriteria(TaskQueryImpl taskQuery) {
        return taskDataManager.findTaskCountByQueryCriteria(taskQuery);
    }

    @Override
    public List<Task> findTasksByNativeQuery(Map<String, Object> parameterMap) {
        return taskDataManager.findTasksByNativeQuery(parameterMap);
    }

    @Override
    public long findTaskCountByNativeQuery(Map<String, Object> parameterMap) {
        return taskDataManager.findTaskCountByNativeQuery(parameterMap);
    }

    @Override
    public List<Task> findTasksByParentTaskId(String parentTaskId) {
        return taskDataManager.findTasksByParentTaskId(parentTaskId);
    }

    @Override
    public void updateTaskTenantIdForDeployment(String deploymentId, String newTenantId) {
        taskDataManager.updateTaskTenantIdForDeployment(deploymentId, newTenantId);
    }
    
    @Override
    public void updateAllTaskRelatedEntityCountFlags(boolean configProperty) {
        taskDataManager.updateAllTaskRelatedEntityCountFlags(configProperty);
    }
    
    @Override
    public void deleteTasksByExecutionId(String executionId) {
        taskDataManager.deleteTasksByExecutionId(executionId);
    }

    public TaskDataManager getTaskDataManager() {
        return taskDataManager;
    }

    public void setTaskDataManager(TaskDataManager taskDataManager) {
        this.taskDataManager = taskDataManager;
    }

    protected String serializeLogEntryData(String... data) {
        ObjectNode dataNode = taskServiceConfiguration.getObjectMapper().createObjectNode();
        for (int i = 0; i < data.length; i += 2) {
            dataNode.put(data[i], data[i + 1]);
        }
        return dataNode.toString();
    }

    protected void logAssigneeChanged(TaskEntity taskEntity, String previousAssignee, String newAssignee) {
        if (this.getTaskServiceConfiguration().isEnableHistoricTaskLogging()) {
            HistoricTaskLogEntryEntity taskLogEntry = createInitialTaskLogEntry(taskEntity);
            taskLogEntry.setType(HistoricTaskLogEntryType.USER_TASK_ASSIGNEE_CHANGED.name());
            taskLogEntry.setData(
                serializeLogEntryData(
                    "newAssigneeId", newAssignee,
                    "previousAssigneeId", previousAssignee
                )
            );
            CommandContextUtil.getHistoricTaskLogEntryEntityManager().insert(taskLogEntry);
        }
    }

    protected void logOwnerChanged(TaskEntity taskEntity, String previousOwner, String newOwner) {
        if (this.getTaskServiceConfiguration().isEnableHistoricTaskLogging()) {
            HistoricTaskLogEntryEntity taskLogEntry = createInitialTaskLogEntry(taskEntity);
            taskLogEntry.setType(HistoricTaskLogEntryType.USER_TASK_OWNER_CHANGED.name());
            taskLogEntry.setData(
                serializeLogEntryData(
                    "newOwnerId", newOwner,
                    "previousOwnerId", previousOwner
                )
            );
            CommandContextUtil.getHistoricTaskLogEntryEntityManager().insert(taskLogEntry);
        }
    }

    protected void logPriorityChanged(TaskEntity taskEntity, Integer previousPriority, int newPriority) {
        if (this.getTaskServiceConfiguration().isEnableHistoricTaskLogging()) {
            HistoricTaskLogEntryEntity taskLogEntry = createInitialTaskLogEntry(taskEntity);
            taskLogEntry.setType(HistoricTaskLogEntryType.USER_TASK_PRIORITY_CHANGED.name());
            ObjectNode dataNode = taskServiceConfiguration.getObjectMapper().createObjectNode();
            dataNode.put("newPriority", newPriority);
            dataNode.put("previousPriority", previousPriority);
            taskLogEntry.setData( dataNode.toString());
            CommandContextUtil.getHistoricTaskLogEntryEntityManager().insert(taskLogEntry);
        }
    }

    protected void logDueDateChanged(TaskEntity taskEntity, Date previousDueDate, Date newDueDate) {
        if (this.getTaskServiceConfiguration().isEnableHistoricTaskLogging()) {
            HistoricTaskLogEntryEntity taskLogEntry = createInitialTaskLogEntry(taskEntity);
            taskLogEntry.setType(HistoricTaskLogEntryType.USER_TASK_DUEDATE_CHANGED.name());
            ObjectNode dataNode = taskServiceConfiguration.getObjectMapper().createObjectNode();
            dataNode.put("newDueDate", newDueDate != null ? newDueDate.getTime() : null);
            dataNode.put("previousDueDate", previousDueDate != null ? previousDueDate.getTime() : null);
            taskLogEntry.setData(dataNode.toString());
            CommandContextUtil.getHistoricTaskLogEntryEntityManager().insert(taskLogEntry);
        }
    }

    protected void logNameChanged(TaskEntity taskEntity, String previousName, String newName) {
        if (this.getTaskServiceConfiguration().isEnableHistoricTaskLogging()) {
            HistoricTaskLogEntryEntity taskLogEntry = createInitialTaskLogEntry(taskEntity);
            taskLogEntry.setType(HistoricTaskLogEntryType.USER_TASK_NAME_CHANGED.name());
            taskLogEntry.setData(
                serializeLogEntryData(
                    "newName", newName,
                    "previousName", previousName
                )
            );
            CommandContextUtil.getHistoricTaskLogEntryEntityManager().insert(taskLogEntry);
        }
    }

    protected void logTaskCreatedEvent(TaskInfo task) {
        if (this.getTaskServiceConfiguration().isEnableHistoricTaskLogging()) {
            HistoricTaskLogEntryEntity taskLogEntry = createInitialTaskLogEntry(task);
            taskLogEntry.setTimeStamp(task.getCreateTime());
            taskLogEntry.setType(HistoricTaskLogEntryType.USER_TASK_CREATED.name());
            CommandContextUtil.getHistoricTaskLogEntryEntityManager().insert(taskLogEntry);
        }
    }

    protected HistoricTaskLogEntryEntity createInitialTaskLogEntry(TaskInfo task) {
        HistoricTaskLogEntryEntity historicTaskLogEntryEntity = CommandContextUtil.getHistoricTaskLogEntryEntityManager().create();
        historicTaskLogEntryEntity.setUserId(Authentication.getAuthenticatedUserId());
        historicTaskLogEntryEntity.setTimeStamp(this.taskServiceConfiguration.getClock().getCurrentTime());
        historicTaskLogEntryEntity.setTaskId(task.getId());
        historicTaskLogEntryEntity.setTenantId(task.getTenantId());
        historicTaskLogEntryEntity.setProcessInstanceId(task.getProcessInstanceId());
        historicTaskLogEntryEntity.setProcessDefinitionId(task.getProcessDefinitionId());
        historicTaskLogEntryEntity.setExecutionId(task.getExecutionId());
        historicTaskLogEntryEntity.setScopeId(task.getScopeId());
        historicTaskLogEntryEntity.setScopeDefinitionId(task.getScopeDefinitionId());
        historicTaskLogEntryEntity.setSubScopeId(task.getSubScopeId());
        historicTaskLogEntryEntity.setScopeType(task.getScopeType());

        return historicTaskLogEntryEntity;
    }

    protected void logTaskUpdateEvents(TaskEntity task) {
        if (!Objects.equals(task.getAssignee(), getOriginalState(task, "assignee"))) {
            logAssigneeChanged(task, (String) getOriginalState(task, "assignee"), task.getAssignee());
        }
        if (!Objects.equals(task.getOwner(), getOriginalState(task, "owner"))) {
            if (getEventDispatcher() != null && getEventDispatcher().isEnabled()) {
                getEventDispatcher().dispatchEvent(FlowableTaskEventBuilder.createEntityEvent(FlowableEngineEventType.TASK_OWNER_CHANGED, task));
            }

            logOwnerChanged(task, (String) getOriginalState(task, "owner"), task.getOwner());
        }
        if (!Objects.equals(task.getPriority(), getOriginalState(task, "priority"))) {
            if (getEventDispatcher() != null && getEventDispatcher().isEnabled()) {
                getEventDispatcher().dispatchEvent(FlowableTaskEventBuilder.createEntityEvent(FlowableEngineEventType.TASK_PRIORITY_CHANGED, task));
            }
            logPriorityChanged(task, (Integer) getOriginalState(task, "priority"), task.getPriority());
        }
        if (!Objects.equals(task.getDueDate(), getOriginalState(task, "dueDate"))) {
            if (getEventDispatcher() != null && getEventDispatcher().isEnabled()) {
                getEventDispatcher().dispatchEvent(FlowableTaskEventBuilder.createEntityEvent(FlowableEngineEventType.TASK_DUEDATE_CHANGED, task));
            }
            logDueDateChanged(task, (Date) getOriginalState(task, "dueDate"), task.getDueDate());
        }
        if (!Objects.equals(task.getName(), getOriginalState(task, "name"))) {
            if (getEventDispatcher() != null && getEventDispatcher().isEnabled()) {
                getEventDispatcher().dispatchEvent(FlowableTaskEventBuilder.createEntityEvent(FlowableEngineEventType.TASK_NAME_CHANGED, task));
            }
            logNameChanged(task, (String) getOriginalState(task, "name"), task.getName());
        }
    }

    protected Object getOriginalState(TaskEntity task, String stateKey) {
        return ((Map<String, Object>) task.getOriginalPersistentState()).get(stateKey);
    }

}
