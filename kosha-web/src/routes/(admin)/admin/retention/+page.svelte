<script lang="ts">
	import { onMount } from 'svelte';
	import { api } from '$lib/api';
	import type { RetentionPolicy } from '$lib/types/api';
	import PageHeader from '$lib/components/kosha/PageHeader.svelte';
	import StatusBadge from '$lib/components/kosha/StatusBadge.svelte';
	import ErrorBoundary from '$lib/components/kosha/ErrorBoundary.svelte';

	let policies = $state<RetentionPolicy[]>([]);
	let loading = $state(true);
	let error = $state('');

	onMount(() => loadPolicies());

	async function loadPolicies() {
		loading = true;
		error = '';
		try {
			const res = await api.retention.list(0, 50);
			policies = res.data;
		} catch (e: any) {
			if (e.message?.includes('404')) policies = [];
			else error = e.message;
		} finally {
			loading = false;
		}
	}
</script>

<svelte:head>
	<title>Retention Policies - Administration - Eòlas</title>
</svelte:head>

<PageHeader title="Retention Policies" description="Document lifecycle and compliance">
	<a href="/admin/retention/new"
		class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-ring">
		+ New Policy
	</a>
</PageHeader>

{#if loading}
	<p aria-live="polite" class="mt-6 text-muted-foreground">Loading policies...</p>
{:else if error}
	<div class="mt-6"><ErrorBoundary {error} onRetry={loadPolicies} /></div>
{:else if policies.length === 0}
	<div class="mt-8 text-center">
		<p class="text-lg text-muted-foreground">No retention policies configured</p>
		<a href="/admin/retention/new" class="mt-2 inline-block text-sm text-primary underline">Create your first policy</a>
	</div>
{:else}
	<div class="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
		{#each policies as policy}
			<a href="/admin/retention/{policy.id}"
				class="rounded-lg border border-border bg-card p-5 transition hover:border-primary focus:outline-2 focus:outline-offset-2 focus:outline-ring">
				<div class="flex items-start justify-between">
					<h2 class="font-semibold">{policy.name}</h2>
					<StatusBadge status={policy.status} />
				</div>
				{#if policy.description}
					<p class="mt-1 text-sm text-muted-foreground line-clamp-2">{policy.description}</p>
				{/if}
				<dl class="mt-3 space-y-1 text-sm">
					<div class="flex justify-between">
						<dt class="text-muted-foreground">Retention</dt>
						<dd class="font-medium">{policy.retentionPeriod}</dd>
					</div>
					{#if policy.reviewInterval}
						<div class="flex justify-between">
							<dt class="text-muted-foreground">Review</dt>
							<dd class="font-medium">{policy.reviewInterval}</dd>
						</div>
					{/if}
					<div class="flex justify-between">
						<dt class="text-muted-foreground">On Expiry</dt>
						<dd class="font-medium">{policy.actionOnExpiry}</dd>
					</div>
				</dl>
			</a>
		{/each}
	</div>
{/if}
