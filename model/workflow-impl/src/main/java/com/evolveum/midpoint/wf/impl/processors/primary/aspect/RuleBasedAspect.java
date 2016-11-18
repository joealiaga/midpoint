/*
 * Copyright (c) 2010-2016 Evolveum
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.evolveum.midpoint.wf.impl.processors.primary.aspect;

import com.evolveum.midpoint.model.api.context.EvaluatedAssignment;
import com.evolveum.midpoint.model.api.context.EvaluatedPolicyRule;
import com.evolveum.midpoint.model.api.context.EvaluatedPolicyRuleTrigger;
import com.evolveum.midpoint.model.api.context.ModelContext;
import com.evolveum.midpoint.model.impl.lens.LensContext;
import com.evolveum.midpoint.prism.PrismContainerValue;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismReferenceValue;
import com.evolveum.midpoint.prism.delta.*;
import com.evolveum.midpoint.prism.delta.builder.DeltaBuilder;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.prism.query.ObjectQuery;
import com.evolveum.midpoint.prism.query.builder.QueryBuilder;
import com.evolveum.midpoint.schema.ObjectTreeDeltas;
import com.evolveum.midpoint.schema.constants.SchemaConstants;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.schema.util.ObjectTypeUtil;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.util.DebugUtil;
import com.evolveum.midpoint.util.QNameUtil;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import com.evolveum.midpoint.wf.impl.processes.itemApproval.*;
import com.evolveum.midpoint.wf.impl.processors.primary.PcpChildWfTaskCreationInstruction;
import com.evolveum.midpoint.xml.ns._public.common.common_3.*;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang.Validate;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.velocity.util.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.xml.namespace.QName;
import java.util.*;
import java.util.stream.Collectors;

import static com.evolveum.midpoint.schema.util.ObjectTypeUtil.createObjectRef;
import static com.evolveum.midpoint.wf.impl.util.MiscDataUtil.getFocusObjectName;
import static com.evolveum.midpoint.wf.impl.util.MiscDataUtil.getFocusObjectOid;

/**
 *
 * @author mederly
 */
@Component
public class RuleBasedAspect extends BasePrimaryChangeAspect {

    private static final Trace LOGGER = TraceManager.getTrace(RuleBasedAspect.class);

    @Autowired
    protected PrismContext prismContext;

    @Autowired
    protected ItemApprovalProcessInterface itemApprovalProcessInterface;

	@Autowired
	protected ApprovalSchemaHelper approvalSchemaHelper;

    //region ------------------------------------------------------------ Things that execute on request arrival

	@Override
	public boolean isEnabledByDefault() {
		return true;
	}

	@NotNull
	@Override
    public List<PcpChildWfTaskCreationInstruction> prepareTasks(@NotNull ModelContext<?> modelContext,
            WfConfigurationType wfConfigurationType, @NotNull ObjectTreeDeltas objectTreeDeltas,
            @NotNull Task taskFromModel, @NotNull OperationResult result) throws SchemaException {

		List<PcpChildWfTaskCreationInstruction> instructions = new ArrayList<>();
		PrismObject<UserType> requester = baseModelInvocationProcessingHelper.getRequester(taskFromModel, result);

		extractAssignmentBasedInstructions(modelContext, objectTreeDeltas, requester, instructions, wfConfigurationType, taskFromModel, result);
		extractObjectBasedInstructions((LensContext<?>) modelContext, objectTreeDeltas, requester, instructions, taskFromModel, result);
        return instructions;
    }

	private void extractAssignmentBasedInstructions(@NotNull ModelContext<?> modelContext,
			@NotNull ObjectTreeDeltas objectTreeDeltas, PrismObject<UserType> requester,
			List<PcpChildWfTaskCreationInstruction> instructions, WfConfigurationType wfConfigurationType,
			@NotNull Task taskFromModel, @NotNull OperationResult result)
			throws SchemaException {
		DefaultApprovalPolicyApplicationStrategyType applyDefaultPolicy = baseConfigurationHelper.getDefaultPolicyApplicationStrategy(wfConfigurationType);
		DeltaSetTriple<? extends EvaluatedAssignment> evaluatedAssignmentTriple = ((LensContext<?>) modelContext).getEvaluatedAssignmentTriple();
		LOGGER.trace("Processing evaluatedAssignmentTriple:\n{}", DebugUtil.debugDumpLazily(evaluatedAssignmentTriple));
		for (EvaluatedAssignment<?> newAssignment : evaluatedAssignmentTriple.getPlusSet()) {
			LOGGER.trace("Assignment to be added: -> {} ({} policy rules)", newAssignment.getTarget(), newAssignment.getPolicyRules().size());
			List<ApprovalPolicyActionType> approvalActions = new ArrayList<>();
			for (EvaluatedPolicyRule rule : newAssignment.getPolicyRules()) {
				if (rule.getTriggers().isEmpty()) {
					LOGGER.trace("Skipping rule {} that is present but not triggered", rule.getName());
					continue;
				}
				if (rule.getActions() == null || rule.getActions().getApproval() == null) {
					LOGGER.trace("Skipping rule {} that doesn't contain an approval action", rule.getName());
					continue;
				}
				approvalActions.add(rule.getActions().getApproval());
			}
			if (applyDefaultPolicy == DefaultApprovalPolicyApplicationStrategyType.ALWAYS
					|| (applyDefaultPolicy == DefaultApprovalPolicyApplicationStrategyType.IF_NO_OTHER && approvalActions.isEmpty())
					|| (applyDefaultPolicy == DefaultApprovalPolicyApplicationStrategyType.IF_NOT_PRESENT && !containsDefault(approvalActions))) {
				ApprovalPolicyActionType defaultPolicyAction = new ApprovalPolicyActionType(prismContext);
				defaultPolicyAction.setUseDefaultApprovers(true);
				approvalActions.add(0, defaultPolicyAction);		// it is logical to place default processing at the beginning
			}
			if (approvalActions.isEmpty()) {
				LOGGER.trace("This assignment hasn't triggered approval actions, continuing with a next one.");
				continue;
			}
			PrismContainerValue<AssignmentType> assignmentValue = newAssignment.getAssignmentType().asPrismContainerValue();
			boolean removed = objectTreeDeltas.subtractFromFocusDelta(new ItemPath(FocusType.F_ASSIGNMENT), assignmentValue);
			if (!removed) {
				String message = "Assignment with a value of " + assignmentValue.debugDump() + " was not found in deltas: "
						+ objectTreeDeltas.debugDump();
				assert false : message;				// fail in test mode; just log an error otherwise
				LOGGER.error("{}", message);
				return;
			}
			ApprovalRequest<?> request = createAssignmentApprovalRequest(newAssignment, approvalActions, result);
			if (!request.getApprovalSchema().isEmpty()) {
				instructions.add(
						prepareAssignmentRelatedTaskInstruction(request, newAssignment, modelContext, taskFromModel, requester,
								result));
			}
		}
	}

	private boolean containsDefault(List<ApprovalPolicyActionType> approvalActions) {
		return approvalActions.stream().anyMatch(a -> Boolean.TRUE.equals(a.isUseDefaultApprovers()));
	}

	private void extractObjectBasedInstructions(@NotNull LensContext<?> modelContext,
			@NotNull ObjectTreeDeltas objectTreeDeltas, PrismObject<UserType> requester,
			List<PcpChildWfTaskCreationInstruction> instructions, @NotNull Task taskFromModel, @NotNull OperationResult result)
			throws SchemaException {

		ObjectDelta<?> focusDelta = objectTreeDeltas.getFocusChange();
		if (focusDelta == null) {
			return;
		}

		Map<Set<ItemPath>, ApprovalSchemaType> approvalSchemas = new HashMap<>();

		Collection<EvaluatedPolicyRule> policyRules = modelContext.getFocusContext().getPolicyRules();
		for (EvaluatedPolicyRule rule : policyRules) {
			LOGGER.trace("Processing object-level policy rule:\n{}", DebugUtil.debugDumpLazily(rule));
			if (rule.getTriggers().isEmpty()) {
				LOGGER.trace("Skipping the rule because it is not triggered", rule.getName());
				continue;
			}
			if (rule.getActions() == null || rule.getActions().getApproval() == null) {
				LOGGER.trace("Skipping the rule because it doesn't contain an approval action", rule.getName());
				continue;
			}
			Set<ItemPath> key;
			if (focusDelta.isAdd() || focusDelta.isDelete()) {
				key = Collections.emptySet();
			} else {
				Set<ItemPath> items = getAffectedItems(rule.getTriggers());
				Set<ItemPath> deltaItems = new HashSet<>(focusDelta.getModifiedItems());
				Set<ItemPath> affectedItems;
				if (items.isEmpty()) {
					affectedItems = deltaItems;        // whole object
				} else {
					affectedItems = new HashSet<>(CollectionUtils.intersection(items, deltaItems));
				}
				key = affectedItems;
			}
			approvalSchemas.put(key,
					addApprovalActionIntoApprovalSchema(approvalSchemas.get(key), rule.getActions().getApproval(), null));
		}

		// create approval requests; also test for overlaps
		Set<ItemPath> itemsProcessed = null;
		for (Map.Entry<Set<ItemPath>, ApprovalSchemaType> entry : approvalSchemas.entrySet()) {
			Set<ItemPath> items = entry.getKey();
			if (itemsProcessed != null) {
				if (items.isEmpty() || itemsProcessed.isEmpty() || CollectionUtils.containsAny(itemsProcessed, items)) {
					throw new IllegalStateException("Overlapping modification-related policy rules. "
							+ "Items processed = " + itemsProcessed + ", current items = " + items);
				}
				itemsProcessed.addAll(items);
			} else {
				itemsProcessed = items;
			}
			ApprovalRequest<?> request = new ApprovalRequestImpl<>("dummy", entry.getValue(), prismContext);
			instructions.add(
					prepareObjectRelatedTaskInstruction(request, focusDelta, items, modelContext, taskFromModel, requester, result));
		}
	}

	private Set<ItemPath> getAffectedItems(Collection<EvaluatedPolicyRuleTrigger> triggers) {
		Set<ItemPath> rv = new HashSet<>();
		for (EvaluatedPolicyRuleTrigger trigger : triggers) {
			if (trigger.getConstraint() instanceof ModificationPolicyConstraintType) {
				ModificationPolicyConstraintType modConstraint = (ModificationPolicyConstraintType) trigger.getConstraint();
				if (modConstraint.getItem().isEmpty()) {
					return Collections.emptySet();			// all items
				} else {
					modConstraint.getItem().forEach(
							itemPathType -> rv.add(itemPathType.getItemPath()));
				}
			}
		}
		return rv;
	}

	private ApprovalRequest<AssignmentType> createAssignmentApprovalRequest(EvaluatedAssignment<?> newAssignment,
			List<ApprovalPolicyActionType> approvalActions, OperationResult result) throws SchemaException {
		ApprovalSchemaType approvalSchema = null;
		for (ApprovalPolicyActionType action : approvalActions) {
			List<ObjectReferenceType> additionalReviewers = new ArrayList<>();
			if (BooleanUtils.isTrue(action.isUseDefaultApprovers())) {
				PrismObject<?> target = newAssignment.getTarget();
				if (target != null && target.asObjectable() instanceof AbstractRoleType) {
					AbstractRoleType abstractRole = (AbstractRoleType) target.asObjectable();
					additionalReviewers.addAll(abstractRole.getApproverRef());
					additionalReviewers.addAll(findApproversByReference(target, result));
					action = action.clone();
					action.getApproverExpression().addAll(abstractRole.getApproverExpression());
					if (abstractRole.getApprovalSchema() != null) {
						if (action.getApprovalSchema() == null) {
							action.setApprovalSchema(abstractRole.getApprovalSchema());
						} else if (!action.getApprovalSchema().equals(abstractRole.getApprovalSchema())) {
							throw new IllegalStateException(
									"Conflicting approval schemas coming from approval policy rule and abstract role "
											+ abstractRole);
						}
					}
					if (abstractRole.getAutomaticallyApproved() != null) {
						if (action.getAutomaticallyApproved() == null) {
							action.setAutomaticallyApproved(abstractRole.getAutomaticallyApproved());
						} else if (!action.getAutomaticallyApproved().equals(abstractRole.getAutomaticallyApproved())) {
							throw new IllegalStateException("Conflicting 'automatically approved' expressions coming from approval policy rule and abstract role "
									+ abstractRole);
						}
					}
				}
			}
			approvalSchema = addApprovalActionIntoApprovalSchema(approvalSchema, action, additionalReviewers);
		}
		assert approvalSchema != null;
		return new ApprovalRequestImpl<>(newAssignment.getAssignmentType(), approvalSchema, prismContext);
	}

	private Collection<? extends ObjectReferenceType> findApproversByReference(PrismObject<?> target, OperationResult result)
			throws SchemaException {
		PrismReferenceValue approverReference = new PrismReferenceValue(target.getOid());
		approverReference.setRelation(SchemaConstants.ORG_APPROVER);
		ObjectQuery query = QueryBuilder.queryFor(FocusType.class, prismContext)
				.item(FocusType.F_ROLE_MEMBERSHIP_REF).ref(approverReference)
				.build();
		LOGGER.trace("Looking for approvers for {} using query:\n{}", target, DebugUtil.debugDumpLazily(query));
		List<PrismObject<FocusType>> objects = repositoryService.searchObjects(FocusType.class, query, null, result);
		Set<PrismObject<FocusType>> distinctObjects = new HashSet<>(objects);
		LOGGER.trace("Found {} approver(s): {}", distinctObjects.size(), DebugUtil.toStringLazily(distinctObjects));
		return distinctObjects.stream()
				.map(ObjectTypeUtil::createObjectRef)
				.collect(Collectors.toList());
	}

	private ApprovalSchemaType addApprovalActionIntoApprovalSchema(ApprovalSchemaType approvalSchema, ApprovalPolicyActionType action,
			@Nullable List<ObjectReferenceType> additionalReviewers) {
		if (action.getApprovalSchema() != null) {
			approvalSchema = approvalSchemaHelper.mergeIntoSchema(approvalSchema, action.getApprovalSchema());
		} else {
			approvalSchema = approvalSchemaHelper
					.mergeIntoSchema(approvalSchema, action.getApproverExpression(), action.getAutomaticallyApproved(), additionalReviewers);
		}
		return approvalSchema;
	}

	private PcpChildWfTaskCreationInstruction prepareAssignmentRelatedTaskInstruction(ApprovalRequest<?> approvalRequest,
			EvaluatedAssignment<?> evaluatedAssignment, ModelContext<?> modelContext, Task taskFromModel,
			PrismObject<UserType> requester, OperationResult result) throws SchemaException {

		String objectOid = getFocusObjectOid(modelContext);
		String objectName = getFocusObjectName(modelContext);

		assert approvalRequest.getPrismContext() != null;

		LOGGER.trace("Approval request = {}", approvalRequest);

		PrismObject<? extends ObjectType> target = (PrismObject<? extends ObjectType>) evaluatedAssignment.getTarget();
		Validate.notNull(target, "assignment target is null");

		String targetName = target.getName() != null ? target.getName().getOrig() : "(unnamed)";
		String approvalTaskName = "Approve adding " + targetName + " to " + objectName;				// TODO adding?

		PcpChildWfTaskCreationInstruction<ItemApprovalSpecificContent> instruction =
				PcpChildWfTaskCreationInstruction.createItemApprovalInstruction(getChangeProcessor(), approvalTaskName, approvalRequest);

		instruction.prepareCommonAttributes(this, modelContext, requester);

		ObjectDelta<? extends FocusType> delta = assignmentToDelta(evaluatedAssignment.getAssignmentType(), objectOid);
		instruction.setDeltasToProcess(delta);

		instruction.setObjectRef(modelContext, result);
		instruction.setTargetRef(createObjectRef(target), result);

		String andExecuting = instruction.isExecuteApprovedChangeImmediately() ? "and execution " : "";
		instruction.setTaskName("Approval " + andExecuting + "of assigning " + targetName + " to " + objectName);
		instruction.setProcessInstanceName("Assigning " + targetName + " to " + objectName);

		itemApprovalProcessInterface.prepareStartInstruction(instruction);

		return instruction;
    }

	private PcpChildWfTaskCreationInstruction prepareObjectRelatedTaskInstruction(ApprovalRequest<?> approvalRequest,
			ObjectDelta<?> focusDelta, Set<ItemPath> paths, ModelContext<?> modelContext, Task taskFromModel,
			PrismObject<UserType> requester, OperationResult result) throws SchemaException {

		//String objectOid = getFocusObjectOid(modelContext);
		String objectName = getFocusObjectName(modelContext);

		assert approvalRequest.getPrismContext() != null;

		LOGGER.trace("Approval request = {}", approvalRequest);

		String opName;
		if (focusDelta.isAdd()) {
			opName = "addition";
		} else if (focusDelta.isDelete()) {
			opName = "deletion";
		} else {
			opName = "modification";
		}

		String approvalTaskName = "Approve " + opName + " of " + objectName;

		PcpChildWfTaskCreationInstruction<ItemApprovalSpecificContent> instruction =
				PcpChildWfTaskCreationInstruction.createItemApprovalInstruction(getChangeProcessor(), approvalTaskName, approvalRequest);

		instruction.prepareCommonAttributes(this, modelContext, requester);

		ObjectDelta<? extends FocusType> delta = (ObjectDelta<? extends FocusType>) itemsToDelta(focusDelta, paths);
		instruction.setDeltasToProcess(delta);

		instruction.setObjectRef(modelContext, result);

		String andExecuting = instruction.isExecuteApprovedChangeImmediately() ? "and execution " : "";
		instruction.setTaskName("Approval " + andExecuting + "of " + opName + " of " + objectName);
		instruction.setProcessInstanceName(StringUtils.capitalizeFirstLetter(opName) + " of " + objectName);

		itemApprovalProcessInterface.prepareStartInstruction(instruction);

		return instruction;
	}

	private ObjectDelta<?> itemsToDelta(@NotNull ObjectDelta<?> focusDelta, @NotNull Set<ItemPath> itemPaths) {
		if (itemPaths.isEmpty()) {
			return focusDelta;
		}
		if (!focusDelta.isModify()) {
			throw new IllegalStateException("Not a MODIFY delta; delta = " + focusDelta);
		}
		return focusDelta.subtract(itemPaths);
	}

	// creates an ObjectDelta that will be executed after successful approval of the given assignment
	@SuppressWarnings("unchecked")
    private ObjectDelta<? extends FocusType> assignmentToDelta(AssignmentType assignmentType, String objectOid) throws SchemaException {
		return (ObjectDelta<? extends FocusType>) DeltaBuilder.deltaFor(FocusType.class, prismContext)
				.item(FocusType.F_ASSIGNMENT).add(assignmentType.clone().asPrismContainerValue())
				.asObjectDelta(objectOid);
    }

    //endregion

}