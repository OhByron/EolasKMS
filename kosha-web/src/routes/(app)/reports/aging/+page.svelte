<script lang="ts">
	import { onMount } from 'svelte';
	import { api } from '$lib/api';
	import type { AgingReportRow, AgingReportSummary } from '$lib/types/api';
	import PageHeader from '$lib/components/kosha/PageHeader.svelte';
	import StatusBadge from '$lib/components/kosha/StatusBadge.svelte';
	import ErrorBoundary from '$lib/components/kosha/ErrorBoundary.svelte';

	let rows = $state<AgingReportRow[]>([]);
	let summary = $state<AgingReportSummary | null>(null);
	let total = $state(0);
	let loading = $state(true);
	let error = $state('');
	let currentPage = $state(0);
	const pageSize = 50;

	let filterDept = $state('');
	let filterStatus = $state('');

	// Derive department list from summary data (avoids calling protected departments API)
	const departments = $derived(
		summary?.byDepartment.map((d) => ({ id: d.departmentId, name: d.departmentName })) ?? []
	);

	onMount(() => loadReport());

	async function loadReport() {
		loading = true;
		error = '';
		try {
			const [rowRes, sumRes] = await Promise.all([
				api.reports.aging(currentPage, pageSize, filterDept || undefined, filterStatus || undefined),
				api.reports.agingSummary(filterDept || undefined),
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

	function ageBandColor(band: string): string {
		if (band.startsWith('7')) return 'bg-destructive/15 text-destructive';
		if (band.startsWith('5')) return 'bg-warning/20 text-warning';
		if (band.startsWith('3')) return 'bg-orange-500/15 text-orange-600';
		if (band.startsWith('1')) return 'bg-primary/10 text-primary';
		return 'bg-muted text-muted-foreground';
	}
</script>

<svelte:head>
	<title>Document Aging Report - Kosha</title>
</svelte:head>

<PageHeader title="Document Aging" description="Age distribution and retention status across the document library" />

<!-- Summary cards -->
{#if summary}
	<div class="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-4" role="region" aria-label="Aging summary">
		<div class="rounded-lg border border-border bg-card p-4">
			<p class="text-sm text-muted-foreground">Total documents</p>
			<p class="mt-1 text-2xl font-bold">{summary.totalDocuments.toLocaleString()}</p>
		</div>
		{#each summary.byAgeBand as band}
			<div class="rounded-lg border border-border bg-card p-4">
				<p class="text-sm text-muted-foreground">{band.ageBand}</p>
				<p class="mt-1 text-2xl font-bold">{band.count.toLocaleString()}</p>
			</div>
		{/each}
	</div>

	<!-- Department breakdown -->
	{#if summary.byDepartment.length > 0}
		<details class="mt-4">
			<summary class="cursor-pointer text-sm font-medium text-primary hover:underline focus:outline-2 focus:outline-ring">
				Department breakdown
			</summary>
			<div class="mt-2 overflow-x-auto rounded-lg border border-border">
				<table class="w-full text-sm">
					<thead>
						<tr class="border-b border-border bg-muted/50">
							<th class="px-4 py-2 text-left font-medium">Department</th>
							<th class="px-4 py-2 text-right font-medium">Documents</th>
							<th class="px-4 py-2 text-right font-medium">Avg age (days)</th>
							<th class="px-4 py-2 text-right font-medium">Oldest (days)</th>
						</tr>
					</thead>
					<tbody>
						{#each summary.byDepartment as dept}
							<tr class="border-b border-border last:border-0">
								<td class="px-4 py-2">{dept.departmentName}</td>
								<td class="px-4 py-2 text-right">{dept.totalDocuments.toLocaleString()}</td>
								<td class="px-4 py-2 text-right">{dept.avgAgeDays.toLocaleString()}</td>
								<td class="px-4 py-2 text-right">{dept.oldestDocumentDays.toLocaleString()}</td>
							</tr>
						{/each}
					</tbody>
				</table>
			</div>
		</details>
	{/if}
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
	<div>
		<label for="status-filter" class="block text-xs font-medium text-muted-foreground">Status</label>
		<select id="status-filter" bind:value={filterStatus} onchange={applyFilters}
			class="mt-1 rounded-md border border-border bg-background px-3 py-1.5 text-sm focus:outline-2 focus:outline-ring">
			<option value="">All statuses</option>
			{#each ['DRAFT', 'IN_REVIEW', 'PUBLISHED', 'ARCHIVED', 'SUPERSEDED', 'LEGAL_HOLD', 'REJECTED'] as s}
				<option value={s}>{s}</option>
			{/each}
		</select>
	</div>
	<span class="pb-1.5 text-sm text-muted-foreground">{total.toLocaleString()} document{total !== 1 ? 's' : ''}</span>
</div>

<!-- Data table -->
{#if loading}
	<p aria-live="polite" class="mt-6 text-muted-foreground">Loading aging report...</p>
{:else if error}
	<div class="mt-6"><ErrorBoundary {error} onRetry={loadReport} /></div>
{:else if rows.length === 0}
	<p class="mt-6 text-muted-foreground">No documents match the current filters.</p>
{:else}
	<div class="mt-4 overflow-x-auto rounded-lg border border-border">
		<table class="w-full text-sm">
			<thead>
				<tr class="border-b border-border bg-muted/50">
					<th class="px-4 py-2 text-left font-medium">Document</th>
					<th class="px-4 py-2 text-left font-medium">Department</th>
					<th class="px-4 py-2 text-left font-medium">Status</th>
					<th class="px-4 py-2 text-left font-medium">Policy</th>
					<th class="px-4 py-2 text-right font-medium">Age (days)</th>
					<th class="px-4 py-2 text-left font-medium">Age band</th>
					<th class="px-4 py-2 text-left font-medium">Overdue</th>
				</tr>
			</thead>
			<tbody>
				{#each rows as row}
					<tr class="border-b border-border last:border-0 hover:bg-muted/30">
						<td class="px-4 py-2">
							<a href="/documents/{row.documentId}" class="font-medium text-primary hover:underline focus:outline-2 focus:outline-ring">
								{row.docNumber}
							</a>
							<p class="truncate text-xs text-muted-foreground" style="max-width: 20rem">{row.title}</p>
						</td>
						<td class="px-4 py-2">{row.departmentName}</td>
						<td class="px-4 py-2"><StatusBadge status={row.status} /></td>
						<td class="px-4 py-2 text-xs">{row.retentionPolicyName ?? '—'}</td>
						<td class="px-4 py-2 text-right font-mono">{row.ageDays.toLocaleString()}</td>
						<td class="px-4 py-2">
							<span class="rounded-md px-2 py-0.5 text-xs font-medium {ageBandColor(row.ageBand)}">
								{row.ageBand}
							</span>
						</td>
						<td class="px-4 py-2 text-center">
							{#if row.hasOverdueReview}
								<span class="text-destructive" aria-label="Has overdue review" title="Has overdue review">●</span>
							{/if}
						</td>
					</tr>
				{/each}
			</tbody>
		</table>
	</div>

	{#if total > pageSize}
		<nav aria-label="Aging report pagination" class="mt-4 flex items-center justify-between text-sm">
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
