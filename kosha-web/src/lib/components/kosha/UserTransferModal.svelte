<script lang="ts">
	import { onMount } from 'svelte';
	import { api } from '$lib/api';
	import type { Department, UserProfile } from '$lib/types/api';

	/**
	 * Modal for transferring a user from their current department to another.
	 *
	 * Used by the department detail page's Team tab — a dept admin can move
	 * users out of their team (e.g. when someone changes roles). The receiving
	 * department's admin is not notified or asked for consent in this v1 model;
	 * that's a future enhancement.
	 */
	let {
		open = $bindable(false),
		user = null,
		onTransferred = (_updated: UserProfile) => {},
	}: {
		open?: boolean;
		user?: UserProfile | null;
		onTransferred?: (updated: UserProfile) => void;
	} = $props();

	let departments = $state<Department[]>([]);
	let loadingDepartments = $state(false);
	let targetDepartmentId = $state('');
	let submitting = $state(false);
	let error = $state('');

	$effect(() => {
		if (open && departments.length === 0) {
			loadDepartments();
		}
		if (open) {
			targetDepartmentId = '';
			error = '';
		}
	});

	async function loadDepartments() {
		loadingDepartments = true;
		try {
			const res = await api.departments.list(0, 100);
			departments = res.data;
		} catch (e: any) {
			error = `Failed to load departments: ${e.message}`;
		} finally {
			loadingDepartments = false;
		}
	}

	// Filter out the user's current department — they can't "transfer" to where they already are
	const otherDepartments = $derived(
		user
			? departments.filter((d) => d.id !== user.departmentId)
			: departments
	);

	async function confirm(e: Event) {
		e.preventDefault();
		if (!user || !targetDepartmentId || submitting) return;

		submitting = true;
		error = '';
		try {
			const res = await api.users.update(user.id, {
				departmentId: targetDepartmentId,
			});
			onTransferred(res.data);
			open = false;
		} catch (e: any) {
			error = e.message ?? 'Transfer failed';
		} finally {
			submitting = false;
		}
	}

	function close() {
		open = false;
	}
</script>

{#if open && user}
	<!-- svelte-ignore a11y_click_events_have_key_events a11y_no_static_element_interactions -->
	<div
		class="fixed inset-0 z-40 bg-black/50"
		onclick={close}
		role="presentation"
	></div>

	<div
		class="fixed left-1/2 top-1/2 z-50 w-full max-w-md -translate-x-1/2 -translate-y-1/2 rounded-lg border border-border bg-card p-6 shadow-xl"
		role="dialog"
		aria-modal="true"
		aria-labelledby="transfer-title"
	>
		<h2 id="transfer-title" class="text-lg font-semibold">Transfer team member</h2>

		<p class="mt-2 text-sm text-muted-foreground">
			Move <strong class="text-foreground">{user.displayName}</strong> from
			<strong class="text-foreground">{user.departmentName}</strong> to another department.
			They will lose access to documents scoped to the current department.
		</p>

		<form onsubmit={confirm} class="mt-4 space-y-4">
			<div>
				<label for="target-dept" class="block text-sm font-medium">
					New department <span class="text-destructive">*</span>
				</label>
				<select
					id="target-dept"
					bind:value={targetDepartmentId}
					required
					class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
				>
					<option value="">{loadingDepartments ? 'Loading...' : 'Select a department'}</option>
					{#each otherDepartments as d}
						<option value={d.id}>{d.name}</option>
					{/each}
				</select>
			</div>

			{#if error}
				<div
					role="alert"
					class="rounded-md border border-destructive bg-destructive/10 p-3 text-sm text-destructive"
				>
					{error}
				</div>
			{/if}

			<div class="flex justify-end gap-2 pt-2">
				<button
					type="button"
					onclick={close}
					disabled={submitting}
					class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-ring disabled:opacity-50"
				>
					Cancel
				</button>
				<button
					type="submit"
					disabled={!targetDepartmentId || submitting}
					class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-ring disabled:opacity-50 disabled:cursor-not-allowed"
				>
					{submitting ? 'Transferring...' : 'Transfer'}
				</button>
			</div>
		</form>
	</div>
{/if}
