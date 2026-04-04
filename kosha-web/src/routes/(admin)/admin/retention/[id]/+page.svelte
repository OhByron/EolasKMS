<script lang="ts">
	import { onMount } from 'svelte';
	import { page } from '$app/state';
	import { goto } from '$app/navigation';
	import { api } from '$lib/api';
	import type { RetentionPolicy } from '$lib/types/api';
	import PageHeader from '$lib/components/kosha/PageHeader.svelte';
	import StatusBadge from '$lib/components/kosha/StatusBadge.svelte';
	import ErrorBoundary from '$lib/components/kosha/ErrorBoundary.svelte';

	let policy = $state<RetentionPolicy | null>(null);
	let loading = $state(true);
	let saving = $state(false);
	let error = $state('');

	let editName = $state('');
	let editDesc = $state('');
	let editAction = $state('ARCHIVE');
	let editStatus = $state('ACTIVE');

	const policyId = $derived(page.params.id);

	onMount(() => loadPolicy());

	async function loadPolicy() {
		loading = true;
		try {
			const res = await api.retention.get(policyId);
			policy = res.data;
			editName = res.data.name;
			editDesc = res.data.description ?? '';
			editAction = res.data.actionOnExpiry;
			editStatus = res.data.status;
		} catch (e: any) {
			error = e.message;
		} finally {
			loading = false;
		}
	}

	async function savePolicy(e: Event) {
		e.preventDefault();
		saving = true;
		error = '';
		try {
			const res = await api.retention.update(policyId, {
				name: editName,
				description: editDesc || null,
				actionOnExpiry: editAction,
				status: editStatus
			});
			policy = res.data;
		} catch (e: any) {
			error = e.message;
		} finally {
			saving = false;
		}
	}
</script>

<svelte:head>
	<title>{policy?.name ?? 'Policy'} - Retention - Kosha</title>
</svelte:head>

{#if loading}
	<p aria-live="polite" class="text-muted-foreground">Loading policy...</p>
{:else if error && !policy}
	<ErrorBoundary {error} onRetry={loadPolicy} />
{:else if policy}
	<PageHeader title={policy.name}>
		<a href="/admin/retention" class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-ring">
			← All Policies
		</a>
	</PageHeader>

	{#if error}
		<div role="alert" class="mt-4 rounded-md border border-destructive bg-destructive/10 p-3 text-sm text-destructive">{error}</div>
	{/if}

	<div class="mt-6 grid gap-6 lg:grid-cols-3">
		<div class="lg:col-span-2">
			<form onsubmit={savePolicy} class="rounded-lg border border-border bg-card p-5 space-y-4">
				<div>
					<label for="pol-name" class="block text-sm font-medium">Name</label>
					<input id="pol-name" type="text" bind:value={editName} required
						class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:outline-2 focus:outline-ring" />
				</div>
				<div>
					<label for="pol-desc" class="block text-sm font-medium">Description</label>
					<textarea id="pol-desc" bind:value={editDesc} rows="3"
						class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:outline-2 focus:outline-ring"></textarea>
				</div>
				<div>
					<label for="pol-action" class="block text-sm font-medium">Action on Expiry</label>
					<select id="pol-action" bind:value={editAction}
						class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:outline-2 focus:outline-ring">
						<option value="ARCHIVE">Archive</option>
						<option value="DELETE">Delete</option>
						<option value="REVIEW">Review</option>
					</select>
				</div>
				<div>
					<label for="pol-status" class="block text-sm font-medium">Status</label>
					<select id="pol-status" bind:value={editStatus}
						class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:outline-2 focus:outline-ring">
						<option value="ACTIVE">Active</option>
						<option value="INACTIVE">Inactive</option>
					</select>
				</div>
				<div class="flex justify-end pt-2">
					<button type="submit" disabled={saving || !editName.trim()}
						class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-ring disabled:opacity-50">
						{saving ? 'Saving...' : 'Save Changes'}
					</button>
				</div>
			</form>
		</div>

		<div class="space-y-4">
			<section class="rounded-lg border border-border bg-card p-5">
				<h2 class="mb-3 text-sm font-semibold text-muted-foreground">Policy Details</h2>
				<dl class="space-y-2 text-sm">
					<div><dt class="text-muted-foreground">Retention Period</dt><dd class="font-medium">{policy.retentionPeriod}</dd></div>
					{#if policy.reviewInterval}
						<div><dt class="text-muted-foreground">Review Interval</dt><dd class="font-medium">{policy.reviewInterval}</dd></div>
					{/if}
					<div><dt class="text-muted-foreground">Action on Expiry</dt><dd class="font-medium">{policy.actionOnExpiry}</dd></div>
					<div><dt class="text-muted-foreground">Status</dt><dd><StatusBadge status={policy.status} /></dd></div>
					<div><dt class="text-muted-foreground">Created</dt><dd class="font-medium">{new Date(policy.createdAt).toLocaleDateString()}</dd></div>
				</dl>
			</section>
		</div>
	</div>
{/if}
