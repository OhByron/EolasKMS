<script lang="ts">
	import { api } from '$lib/api';
	import type {
		ActionType,
		UpdateWorkflowStepRequest,
		UserProfile,
		WorkflowDefinition,
		WorkflowType,
		WorkflowValidationResponse,
	} from '$lib/types/api';
	import { onMount } from 'svelte';

	/**
	 * Department workflow configuration editor.
	 *
	 * A department admin uses this to configure how documents submitted in
	 * their department flow through review and approval. The editor works
	 * against a local draft — changes are not saved until the user hits Save.
	 * A PUT replaces the whole workflow atomically.
	 *
	 * Assignee and escalation pickers are populated from the department's
	 * team members (passed in as a prop — the parent page already fetches
	 * them, so we avoid duplicating the request).
	 */

	let {
		departmentId,
		departmentMembers = [],
	}: {
		departmentId: string;
		departmentMembers?: UserProfile[];
	} = $props();

	// Server state
	let workflow = $state<WorkflowDefinition | null>(null);
	let validation = $state<WorkflowValidationResponse | null>(null);
	let loading = $state(true);
	let loadError = $state('');

	// Draft state — what the user is editing before save
	type Draft = {
		workflowType: WorkflowType;
		steps: DraftStep[];
	};
	type DraftStep = {
		// clientKey is a stable identity for the {#each} keyed block so
		// reorder/add/remove don't confuse Svelte. Not sent to the server.
		clientKey: string;
		name: string;
		actionType: ActionType;
		assigneeId: string;
		escalationId: string;
		timeLimitDays: number;
	};

	let draft = $state<Draft | null>(null);
	let saving = $state(false);
	let saveError = $state('');
	let saveSuccess = $state('');

	// Only active members can be assigned to workflow steps
	const activeMembers = $derived(departmentMembers.filter((m) => m.status === 'ACTIVE'));

	// True when the draft differs from the loaded workflow (enables the Save button)
	const dirty = $derived(draftDiffers());

	// Draft is valid for submission when every step has required fields
	// and workflow has at least one step. Server-side validation is the
	// authoritative check, but this gates the Save button to reduce round-trips.
	const localValid = $derived(checkLocalValidity());

	onMount(() => load());

	async function load() {
		loading = true;
		loadError = '';
		try {
			const [wfRes, valRes] = await Promise.all([
				api.departments.getWorkflow(departmentId).catch((e) => {
					// 404 is expected when a department has no workflow yet —
					// we surface an empty editor state
					if (String(e.message).includes('no workflow')) return null;
					throw e;
				}),
				api.departments.validateWorkflow(departmentId),
			]);
			workflow = wfRes?.data ?? null;
			validation = valRes.data;
			draft = toDraft(workflow);
		} catch (e: any) {
			loadError = e.message ?? 'Failed to load workflow';
		} finally {
			loading = false;
		}
	}

	function toDraft(wf: WorkflowDefinition | null): Draft {
		if (!wf) {
			return { workflowType: 'LINEAR', steps: [] };
		}
		return {
			workflowType: wf.workflowType,
			steps: wf.steps.map((s) => ({
				clientKey: s.id,
				name: s.name,
				actionType: s.actionType,
				assigneeId: s.assigneeId ?? '',
				escalationId: s.escalationId ?? '',
				timeLimitDays: s.timeLimitDays,
			})),
		};
	}

	function draftDiffers(): boolean {
		if (!draft) return false;
		const original = toDraft(workflow);
		if (original.workflowType !== draft.workflowType) return true;
		if (original.steps.length !== draft.steps.length) return true;
		for (let i = 0; i < draft.steps.length; i++) {
			const a = original.steps[i];
			const b = draft.steps[i];
			if (!a) return true;
			if (
				a.name !== b.name ||
				a.actionType !== b.actionType ||
				a.assigneeId !== b.assigneeId ||
				a.escalationId !== b.escalationId ||
				a.timeLimitDays !== b.timeLimitDays
			) {
				return true;
			}
		}
		return false;
	}

	function checkLocalValidity(): boolean {
		if (!draft || draft.steps.length === 0) return false;
		return draft.steps.every(
			(s) =>
				s.name.trim().length > 0 &&
				s.assigneeId.length > 0 &&
				s.escalationId.length > 0 &&
				s.timeLimitDays >= 1 &&
				s.timeLimitDays <= 90
		);
	}

	// Default assignees/escalations for new steps — the first active member
	// we find, or empty if no one exists. The user picks the real values.
	function defaultAssigneeId(): string {
		return activeMembers[0]?.id ?? '';
	}
	function defaultEscalationId(): string {
		// Prefer someone different from the assignee default, if possible.
		return activeMembers[1]?.id ?? activeMembers[0]?.id ?? '';
	}

	function newStep(name: string, actionType: ActionType): DraftStep {
		return {
			clientKey: crypto.randomUUID(),
			name,
			actionType,
			assigneeId: defaultAssigneeId(),
			escalationId: defaultEscalationId(),
			timeLimitDays: 3,
		};
	}

	// ── Draft mutations ──────────────────────────────────────────

	function addStep() {
		if (!draft) return;
		draft.steps = [...draft.steps, newStep(`Step ${draft.steps.length + 1}`, 'APPROVE')];
	}

	function addReviewAtStart() {
		if (!draft) return;
		draft.steps = [newStep('Peer Review', 'REVIEW'), ...draft.steps];
	}

	function addSignOffAtEnd() {
		if (!draft) return;
		draft.steps = [...draft.steps, newStep('Sign-off', 'SIGN_OFF')];
	}

	function removeStep(idx: number) {
		if (!draft) return;
		draft.steps = draft.steps.filter((_, i) => i !== idx);
	}

	function moveStepUp(idx: number) {
		if (!draft || idx === 0) return;
		const copy = [...draft.steps];
		[copy[idx - 1], copy[idx]] = [copy[idx], copy[idx - 1]];
		draft.steps = copy;
	}

	function moveStepDown(idx: number) {
		if (!draft || idx >= draft.steps.length - 1) return;
		const copy = [...draft.steps];
		[copy[idx], copy[idx + 1]] = [copy[idx + 1], copy[idx]];
		draft.steps = copy;
	}

	// ── Save / Cancel ────────────────────────────────────────────

	async function save() {
		if (!draft || !localValid || saving) return;
		saving = true;
		saveError = '';
		saveSuccess = '';
		try {
			const body = {
				workflowType: draft.workflowType,
				steps: draft.steps.map(
					(s): UpdateWorkflowStepRequest => ({
						name: s.name.trim(),
						actionType: s.actionType,
						assigneeId: s.assigneeId,
						escalationId: s.escalationId,
						timeLimitDays: s.timeLimitDays,
					})
				),
			};
			const res = await api.departments.updateWorkflow(departmentId, body);
			workflow = res.data;
			draft = toDraft(workflow);
			saveSuccess = 'Workflow saved';
			// Refresh validation so any new problems show immediately
			const valRes = await api.departments.validateWorkflow(departmentId);
			validation = valRes.data;
		} catch (e: any) {
			saveError = e.message ?? 'Failed to save workflow';
		} finally {
			saving = false;
		}
	}

	function cancel() {
		draft = toDraft(workflow);
		saveError = '';
		saveSuccess = '';
	}

	// Clear success banner after a moment so it doesn't stick forever
	$effect(() => {
		if (saveSuccess) {
			const t = setTimeout(() => (saveSuccess = ''), 4000);
			return () => clearTimeout(t);
		}
	});
</script>

<section class="rounded-lg border border-border bg-card p-5" aria-labelledby="wf-heading">
	<div class="flex items-start justify-between">
		<div>
			<h2 id="wf-heading" class="text-sm font-semibold text-muted-foreground">Workflow</h2>
			<p class="mt-1 text-xs text-muted-foreground">
				How documents submitted in this department flow through review and approval.
			</p>
		</div>
		{#if workflow?.isDefault}
			<span class="rounded-md bg-accent/20 px-2 py-0.5 text-xs font-medium text-accent-foreground">
				Default
			</span>
		{/if}
	</div>

	{#if loading}
		<p aria-live="polite" class="mt-4 text-sm text-muted-foreground">Loading workflow…</p>
	{:else if loadError}
		<div role="alert" class="mt-4 rounded-md border border-destructive bg-destructive/10 p-3 text-sm text-destructive">
			{loadError}
			<button type="button" onclick={load} class="ml-2 underline focus:outline-2 focus:outline-ring">
				Retry
			</button>
		</div>
	{:else if draft}
		{#if activeMembers.length === 0}
			<div role="alert" class="mt-4 rounded-md border border-warning bg-warning/10 p-3 text-sm">
				This department has no active members. Add team members before configuring a workflow —
				each step must be assigned to a user, and the escalation contact must also be a member.
			</div>
		{/if}

		{#if validation && !validation.ready && validation.problems.length > 0}
			<div role="alert" class="mt-4 rounded-md border border-warning bg-warning/10 p-3 text-sm">
				<p class="font-medium">This workflow has issues that will block document submission:</p>
				<ul class="mt-1 list-disc pl-5 text-xs">
					{#each validation.problems as problem}
						<li>{problem}</li>
					{/each}
				</ul>
			</div>
		{/if}

		<!-- Workflow type -->
		<fieldset class="mt-4">
			<legend class="text-sm font-medium">Type</legend>
			<div class="mt-2 flex gap-4 text-sm">
				<label class="flex items-center gap-2">
					<input
						type="radio"
						name="wf-type"
						value="LINEAR"
						checked={draft.workflowType === 'LINEAR'}
						onchange={() => (draft!.workflowType = 'LINEAR')}
					/>
					<span>
						Linear
						<span class="text-xs text-muted-foreground">(steps run in sequence)</span>
					</span>
				</label>
				<label class="flex items-center gap-2">
					<input
						type="radio"
						name="wf-type"
						value="PARALLEL"
						checked={draft.workflowType === 'PARALLEL'}
						onchange={() => (draft!.workflowType = 'PARALLEL')}
					/>
					<span>
						Parallel
						<span class="text-xs text-muted-foreground">(all steps fire concurrently)</span>
					</span>
				</label>
			</div>
		</fieldset>

		<!-- Steps -->
		<div class="mt-5 space-y-3">
			<h3 class="text-sm font-medium">Steps</h3>

			{#if draft.steps.length === 0}
				<p class="rounded-md border border-dashed border-border p-4 text-center text-sm text-muted-foreground">
					No steps yet. Add at least one to publish a working workflow.
				</p>
			{/if}

			{#each draft.steps as step, idx (step.clientKey)}
				<div class="rounded-md border border-border bg-background p-3">
					<div class="flex items-start gap-2">
						<!-- Reorder controls -->
						<div class="flex flex-col gap-1 pt-1" role="group" aria-label="Reorder step {idx + 1}">
							<button
								type="button"
								onclick={() => moveStepUp(idx)}
								disabled={idx === 0}
								aria-label="Move step {idx + 1} up"
								class="rounded border border-border px-1 text-xs hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-30"
							>
								↑
							</button>
							<button
								type="button"
								onclick={() => moveStepDown(idx)}
								disabled={idx === draft.steps.length - 1}
								aria-label="Move step {idx + 1} down"
								class="rounded border border-border px-1 text-xs hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-30"
							>
								↓
							</button>
						</div>

						<div class="flex-1 space-y-2">
							<div class="flex items-center gap-2">
								<span class="text-xs font-semibold text-muted-foreground">Step {idx + 1}</span>
								<input
									type="text"
									bind:value={step.name}
									placeholder="Step name"
									aria-label="Step {idx + 1} name"
									maxlength="200"
									class="flex-1 rounded-md border border-border bg-background px-2 py-1 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
								/>
								<button
									type="button"
									onclick={() => removeStep(idx)}
									aria-label="Remove step {idx + 1}"
									class="rounded-md border border-border px-2 py-1 text-xs hover:bg-destructive/10 hover:text-destructive focus:outline-2 focus:outline-offset-2 focus:outline-ring"
								>
									Remove
								</button>
							</div>

							<div class="grid gap-2 sm:grid-cols-2">
								<div>
									<label class="block text-xs font-medium text-muted-foreground" for="step-{idx}-action">
										Action
									</label>
									<select
										id="step-{idx}-action"
										bind:value={step.actionType}
										class="mt-1 w-full rounded-md border border-border bg-background px-2 py-1 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
									>
										<option value="REVIEW">Review</option>
										<option value="APPROVE">Approve</option>
										<option value="SIGN_OFF">Sign-off</option>
									</select>
								</div>
								<div>
									<label class="block text-xs font-medium text-muted-foreground" for="step-{idx}-days">
										Time limit (days)
									</label>
									<input
										id="step-{idx}-days"
										type="number"
										min="1"
										max="90"
										bind:value={step.timeLimitDays}
										class="mt-1 w-full rounded-md border border-border bg-background px-2 py-1 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
									/>
								</div>
								<div>
									<label class="block text-xs font-medium text-muted-foreground" for="step-{idx}-assignee">
										Assignee
									</label>
									<select
										id="step-{idx}-assignee"
										bind:value={step.assigneeId}
										class="mt-1 w-full rounded-md border border-border bg-background px-2 py-1 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
										class:border-destructive={!step.assigneeId}
									>
										<option value="">-- select --</option>
										{#each activeMembers as m}
											<option value={m.id}>{m.displayName} ({m.role})</option>
										{/each}
									</select>
								</div>
								<div>
									<label class="block text-xs font-medium text-muted-foreground" for="step-{idx}-escalation">
										Escalation contact
									</label>
									<select
										id="step-{idx}-escalation"
										bind:value={step.escalationId}
										class="mt-1 w-full rounded-md border border-border bg-background px-2 py-1 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
										class:border-destructive={!step.escalationId}
									>
										<option value="">-- select --</option>
										{#each activeMembers as m}
											<option value={m.id}>{m.displayName} ({m.role})</option>
										{/each}
									</select>
								</div>
							</div>
						</div>
					</div>
				</div>
			{/each}
		</div>

		<!-- Add-step controls -->
		<div class="mt-4 flex flex-wrap gap-2">
			<button
				type="button"
				onclick={addStep}
				disabled={activeMembers.length === 0}
				class="rounded-md border border-border px-3 py-1.5 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
			>
				+ Add step
			</button>
			{#if draft.steps.length === 0 || draft.steps[0]?.actionType !== 'REVIEW'}
				<button
					type="button"
					onclick={addReviewAtStart}
					disabled={activeMembers.length === 0}
					class="rounded-md border border-border px-3 py-1.5 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
				>
					+ Review at start
				</button>
			{/if}
			{#if draft.steps.length === 0 || draft.steps[draft.steps.length - 1]?.actionType !== 'SIGN_OFF'}
				<button
					type="button"
					onclick={addSignOffAtEnd}
					disabled={activeMembers.length === 0}
					class="rounded-md border border-border px-3 py-1.5 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
				>
					+ Sign-off at end
				</button>
			{/if}
		</div>

		<!-- Save/Cancel bar -->
		<div class="mt-5 flex items-center justify-between gap-3 border-t border-border pt-4">
			<div class="text-xs" aria-live="polite">
				{#if saveError}
					<span class="text-destructive">{saveError}</span>
				{:else if saveSuccess}
					<span class="text-success">{saveSuccess}</span>
				{:else if dirty}
					<span class="text-muted-foreground">Unsaved changes</span>
				{:else}
					<span class="text-muted-foreground">No changes</span>
				{/if}
			</div>
			<div class="flex gap-2">
				<button
					type="button"
					onclick={cancel}
					disabled={!dirty || saving}
					class="rounded-md border border-border px-3 py-1.5 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
				>
					Cancel
				</button>
				<button
					type="button"
					onclick={save}
					disabled={!dirty || !localValid || saving}
					class="rounded-md bg-primary px-3 py-1.5 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50 disabled:cursor-not-allowed"
				>
					{saving ? 'Saving…' : 'Save workflow'}
				</button>
			</div>
		</div>
	{/if}
</section>
