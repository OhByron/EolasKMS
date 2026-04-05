<script lang="ts">
	import { onMount } from 'svelte';
	import { api } from '$lib/api';
	import type { CriticalItemRow, CriticalItemsSummary } from '$lib/types/api';
	import PageHeader from '$lib/components/kosha/PageHeader.svelte';
	import StatusBadge from '$lib/components/kosha/StatusBadge.svelte';
	import ErrorBoundary from '$lib/components/kosha/ErrorBoundary.svelte';

	let rows = $state<CriticalItemRow[]>([]);
	let summary = $state<CriticalItemsSummary | null>(null);
	let total = $state(0);
	let loading = $state(true);
	let error = $state('');
	let currentPage = $state(0);
	const pageSize = 50;

	let filterDept = $state('');
	let filterMinDays = $state<number | undefined>(undefined);

	// Selection
	let selectedIds = $state<Set<string>>(new Set());
	let notifying = $state(false);
	let notifyResult = $state('');

	const departments = $derived(
		summary?.byDepartment.map((d) => ({ id: d.departmentId, name: d.departmentName })) ?? []
	);

	const allSelected = $derived(rows.length > 0 && rows.every((r) => selectedIds.has(r.reviewId)));

	onMount(() => loadReport());

	async function loadReport() {
		loading = true;
		error = '';
		selectedIds = new Set();
		notifyResult = '';
		try {
			const [rowRes, sumRes] = await Promise.all([
				api.reports.criticalItems(currentPage, pageSize, filterDept || undefined, filterMinDays),
				api.reports.criticalItemsSummary(filterDept || undefined),
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

	function toggleSelect(reviewId: string) {
		const next = new Set(selectedIds);
		if (next.has(reviewId)) next.delete(reviewId);
		else next.add(reviewId);
		selectedIds = next;
	}

	function toggleAll() {
		if (allSelected) {
			selectedIds = new Set();
		} else {
			selectedIds = new Set(rows.map((r) => r.reviewId));
		}
	}

	async function notifySelected() {
		if (selectedIds.size === 0) return;
		notifying = true;
		notifyResult = '';
		try {
			const res = await api.reports.notifyCriticalSelected([...selectedIds]);
			notifyResult = `Notified ${res.data.notified} document owner${res.data.notified !== 1 ? 's' : ''}`;
			selectedIds = new Set();
		} catch (e: any) {
			notifyResult = `Failed: ${e.message}`;
		} finally {
			notifying = false;
		}
	}

	async function notifyAll() {
		notifying = true;
		notifyResult = '';
		try {
			const res = await api.reports.notifyCriticalAll(filterDept || undefined);
			notifyResult = `Notified ${res.data.notified} document owner${res.data.notified !== 1 ? 's' : ''}`;
		} catch (e: any) {
			notifyResult = `Failed: ${e.message}`;
		} finally {
			notifying = false;
		}
	}

	function severityColor(severity: string): string {
		switch (severity) {
			case 'CRITICAL': return 'bg-destructive text-destructive-foreground';
			case 'HIGH': return 'bg-destructive/20 text-destructive';
			case 'MEDIUM': return 'bg-warning/20 text-warning';
			case 'LOW': return 'bg-muted text-muted-foreground';
			default: return 'bg-muted text-muted-foreground';
		}
	}

	function formatDate(iso: string): string {
		return new Date(iso).toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' });
	}
</script>

<svelte:head>
	<title>Critical Items - Eòlas</title>
</svelte:head>

<PageHeader title="Critical Items" description="Documents with overdue retention reviews requiring immediate attention" />

<!-- Summary cards -->
{#if summary}
	<div class="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-5" role="region" aria-label="Critical items summary">
		<div class="rounded-lg border border-border bg-card p-4">
			<p class="text-sm text-muted-foreground">Total overdue</p>
			<p class="mt-1 text-2xl font-bold text-destructive">{summary.totalCriticalItems.toLocaleString()}</p>
		</div>
		{#each summary.bySeverity as sev}
			<div class="rounded-lg border border-border bg-card p-4">
				<p class="text-sm text-muted-foreground">{sev.severity}</p>
				<p class="mt-1 text-2xl font-bold">{sev.count.toLocaleString()}</p>
			</div>
		{/each}
	</div>

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
							<th class="px-4 py-2 text-right font-medium">Overdue items</th>
							<th class="px-4 py-2 text-right font-medium">Oldest overdue (days)</th>
						</tr>
					</thead>
					<tbody>
						{#each summary.byDepartment as dept}
							<tr class="border-b border-border last:border-0">
								<td class="px-4 py-2">{dept.departmentName}</td>
								<td class="px-4 py-2 text-right">{dept.criticalCount.toLocaleString()}</td>
								<td class="px-4 py-2 text-right">{dept.oldestOverdueDays.toLocaleString()}</td>
							</tr>
						{/each}
					</tbody>
				</table>
			</div>
		</details>
	{/if}
{/if}

<!-- Filters + Actions bar -->
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
		<label for="min-days" class="block text-xs font-medium text-muted-foreground">Min days overdue</label>
		<input id="min-days" type="number" min="0" bind:value={filterMinDays} onchange={applyFilters}
			placeholder="0"
			class="mt-1 w-24 rounded-md border border-border bg-background px-3 py-1.5 text-sm focus:outline-2 focus:outline-ring" />
	</div>
	<span class="pb-1.5 text-sm text-muted-foreground">{total.toLocaleString()} item{total !== 1 ? 's' : ''}</span>

	<!-- Spacer -->
	<div class="flex-1"></div>

	<!-- Notify actions -->
	{#if rows.length > 0}
		<button
			onclick={notifySelected}
			disabled={selectedIds.size === 0 || notifying}
			class="rounded-md border border-border bg-background px-3 py-1.5 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-ring disabled:opacity-50 disabled:cursor-not-allowed"
		>
			Notify Selected ({selectedIds.size})
		</button>
		<button
			onclick={notifyAll}
			disabled={notifying}
			class="rounded-md bg-destructive px-3 py-1.5 text-sm font-medium text-destructive-foreground hover:opacity-90 focus:outline-2 focus:outline-ring disabled:opacity-50 disabled:cursor-not-allowed"
		>
			{notifying ? 'Sending...' : 'Notify All'}
		</button>
	{/if}
</div>

{#if notifyResult}
	<p class="mt-2 text-sm font-medium" class:text-primary={!notifyResult.startsWith('Failed')} class:text-destructive={notifyResult.startsWith('Failed')} aria-live="polite">
		{notifyResult}
	</p>
{/if}

<!-- Data table -->
{#if loading}
	<p aria-live="polite" class="mt-6 text-muted-foreground">Loading critical items...</p>
{:else if error}
	<div class="mt-6"><ErrorBoundary {error} onRetry={loadReport} /></div>
{:else if rows.length === 0}
	<p class="mt-6 text-muted-foreground">No overdue retention reviews found. All clear.</p>
{:else}
	<div class="mt-4 overflow-x-auto rounded-lg border border-border">
		<table class="w-full text-sm">
			<thead>
				<tr class="border-b border-border bg-muted/50">
					<th class="w-10 px-4 py-2">
						<input
							type="checkbox"
							checked={allSelected}
							onchange={toggleAll}
							aria-label="Select all"
							class="rounded focus:ring-ring"
						/>
					</th>
					<th class="px-4 py-2 text-left font-medium">Severity</th>
					<th class="px-4 py-2 text-left font-medium">Document</th>
					<th class="px-4 py-2 text-left font-medium">Department</th>
					<th class="px-4 py-2 text-left font-medium">Status</th>
					<th class="px-4 py-2 text-left font-medium">Policy</th>
					<th class="px-4 py-2 text-left font-medium">Review due</th>
					<th class="px-4 py-2 text-right font-medium">Days overdue</th>
				</tr>
			</thead>
			<tbody>
				{#each rows as row}
					<tr
						class="border-b border-border last:border-0 hover:bg-muted/30"
						class:bg-sky-50={selectedIds.has(row.reviewId)}
					>
						<td class="px-4 py-2">
							<input
								type="checkbox"
								checked={selectedIds.has(row.reviewId)}
								onchange={() => toggleSelect(row.reviewId)}
								aria-label="Select {row.docNumber}"
								class="rounded focus:ring-ring"
							/>
						</td>
						<td class="px-4 py-2">
							<span class="rounded-md px-2 py-0.5 text-xs font-bold {severityColor(row.severity)}">
								{row.severity}
							</span>
						</td>
						<td class="px-4 py-2">
							<a href="/documents/{row.documentId}" class="font-medium text-primary hover:underline focus:outline-2 focus:outline-ring">
								{row.docNumber}
							</a>
							<p class="truncate text-xs text-muted-foreground" style="max-width: 18rem">{row.title}</p>
						</td>
						<td class="px-4 py-2">{row.departmentName}</td>
						<td class="px-4 py-2"><StatusBadge status={row.status} /></td>
						<td class="px-4 py-2 text-xs">
							{row.retentionPolicyName}
							<span class="text-muted-foreground">({row.retentionPeriod})</span>
						</td>
						<td class="px-4 py-2 text-xs">{formatDate(row.reviewDueAt)}</td>
						<td class="px-4 py-2 text-right font-mono font-bold">{row.daysOverdue.toLocaleString()}</td>
					</tr>
				{/each}
			</tbody>
		</table>
	</div>

	{#if total > pageSize}
		<nav aria-label="Critical items pagination" class="mt-4 flex items-center justify-between text-sm">
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
