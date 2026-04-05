<script lang="ts">
	import { onMount } from 'svelte';
	import { api } from '$lib/api';
	import type { LegalHoldRow, LegalHoldSummary } from '$lib/types/api';
	import PageHeader from '$lib/components/kosha/PageHeader.svelte';
	import ErrorBoundary from '$lib/components/kosha/ErrorBoundary.svelte';

	let rows = $state<LegalHoldRow[]>([]);
	let summary = $state<LegalHoldSummary | null>(null);
	let total = $state(0);
	let loading = $state(true);
	let error = $state('');
	let currentPage = $state(0);
	const pageSize = 50;

	let filterDept = $state('');

	const departments = $derived(
		summary?.byDepartment.map((d) => ({ id: d.departmentId, name: d.departmentName })) ?? []
	);

	onMount(() => loadReport());

	async function loadReport() {
		loading = true;
		error = '';
		try {
			const [rowRes, sumRes] = await Promise.all([
				api.reports.legalHolds(currentPage, pageSize, filterDept || undefined),
				api.reports.legalHoldSummary(filterDept || undefined),
			]);
			rows = rowRes.data;
			total = rowRes.meta?.total ?? rows.length;
			summary = sumRes.data;
		} catch (e: any) {
			error = e.message;
		} finally {
			loading = false;
		}
	}

	function applyFilters() {
		currentPage = 0;
		loadReport();
	}

	function formatDate(iso: string): string {
		return new Date(iso).toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' });
	}
</script>

<svelte:head>
	<title>Legal Holds - Eòlas</title>
</svelte:head>

<PageHeader title="Legal Holds" description="Documents currently under legal hold with suspended retention" />

<!-- Summary cards -->
{#if summary}
	<div class="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-4" role="region" aria-label="Legal hold summary">
		<div class="rounded-lg border border-border bg-card p-4">
			<p class="text-sm text-muted-foreground">Total on hold</p>
			<p class="mt-1 text-2xl font-bold">{summary.totalOnHold.toLocaleString()}</p>
		</div>
		{#each summary.byDepartment as dept}
			<div class="rounded-lg border border-border bg-card p-4">
				<p class="text-sm text-muted-foreground">{dept.departmentName}</p>
				<div class="mt-1 flex items-baseline gap-2">
					<span class="text-2xl font-bold">{dept.holdCount.toLocaleString()}</span>
					<span class="text-xs text-muted-foreground">avg {dept.avgHoldDays.toLocaleString()}d</span>
				</div>
			</div>
		{/each}
	</div>
{/if}

<!-- Filters -->
<div class="mt-6 flex flex-wrap items-end gap-3">
	<div>
		<label for="dept-filter" class="block text-xs font-medium text-muted-foreground">Department</label>
		<select id="dept-filter" bind:value={filterDept} onchange={applyFilters}
			class="mt-1 rounded-md border border-border bg-background px-3 py-1.5 text-sm focus:outline-2 focus:outline-ring">
			<option value="">All departments</option>
			{#each departments as d}
				<option value={d.id}>{d.name}</option>
			{/each}
		</select>
	</div>
	<span class="pb-1.5 text-sm text-muted-foreground">{total.toLocaleString()} document{total !== 1 ? 's' : ''} on hold</span>
</div>

<!-- Data table -->
{#if loading}
	<p aria-live="polite" class="mt-6 text-muted-foreground">Loading legal holds...</p>
{:else if error}
	<div class="mt-6"><ErrorBoundary {error} onRetry={loadReport} /></div>
{:else if rows.length === 0}
	<p class="mt-6 text-muted-foreground">No documents are currently on legal hold.</p>
{:else}
	<div class="mt-4 overflow-x-auto rounded-lg border border-border">
		<table class="w-full text-sm">
			<thead>
				<tr class="border-b border-border bg-muted/50">
					<th class="px-4 py-2 text-left font-medium">Document</th>
					<th class="px-4 py-2 text-left font-medium">Department</th>
					<th class="px-4 py-2 text-left font-medium">Category</th>
					<th class="px-4 py-2 text-left font-medium">Created by</th>
					<th class="px-4 py-2 text-left font-medium">Created</th>
					<th class="px-4 py-2 text-right font-medium">On hold (days)</th>
					<th class="px-4 py-2 text-left font-medium">Original policy</th>
					<th class="px-4 py-2 text-left font-medium">Version</th>
				</tr>
			</thead>
			<tbody>
				{#each rows as row}
					<tr class="border-b border-border last:border-0 hover:bg-muted/30">
						<td class="px-4 py-2">
							<a href="/documents/{row.documentId}" class="font-medium text-primary hover:underline focus:outline-2 focus:outline-ring">
								{row.docNumber}
							</a>
							<p class="truncate text-xs text-muted-foreground" style="max-width: 18rem">{row.title}</p>
						</td>
						<td class="px-4 py-2">{row.departmentName}</td>
						<td class="px-4 py-2 text-xs">{row.categoryName ?? '—'}</td>
						<td class="px-4 py-2 text-xs">{row.createdByName ?? '—'}</td>
						<td class="px-4 py-2 text-xs">{formatDate(row.createdAt)}</td>
						<td class="px-4 py-2 text-right font-mono font-bold">{row.holdSinceDays.toLocaleString()}</td>
						<td class="px-4 py-2 text-xs">
							{#if row.retentionPolicyName}
								{row.retentionPolicyName}
								<span class="text-muted-foreground">({row.originalRetentionPeriod})</span>
							{:else}
								<span class="text-muted-foreground">—</span>
							{/if}
						</td>
						<td class="px-4 py-2 text-xs font-mono">{row.latestVersionNumber ?? '—'}</td>
					</tr>
				{/each}
			</tbody>
		</table>
	</div>

	{#if total > pageSize}
		<nav aria-label="Legal holds pagination" class="mt-4 flex items-center justify-between text-sm">
			<p class="text-muted-foreground">Page {currentPage + 1} of {Math.ceil(total / pageSize)}</p>
			<div class="flex gap-2">
				<button onclick={() => { currentPage--; loadReport(); }} disabled={currentPage === 0}
					class="rounded-md border border-border px-3 py-1 hover:bg-muted focus:outline-2 focus:outline-ring disabled:opacity-50 disabled:cursor-not-allowed">Previous</button>
				<button onclick={() => { currentPage++; loadReport(); }} disabled={(currentPage + 1) * pageSize >= total}
					class="rounded-md border border-border px-3 py-1 hover:bg-muted focus:outline-2 focus:outline-ring disabled:opacity-50 disabled:cursor-not-allowed">Next</button>
			</div>
		</nav>
	{/if}
{/if}
