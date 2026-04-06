<script lang="ts">
	import { page } from '$app/state';
	import { goto } from '$app/navigation';
	import { onMount } from 'svelte';
	import { api } from '$lib/api';
	import type { SearchResult, Department } from '$lib/types/api';
	import PageHeader from '$lib/components/kosha/PageHeader.svelte';
	import StatusBadge from '$lib/components/kosha/StatusBadge.svelte';
	import * as m from '$paraglide/messages';

	let query = $state('');
	let results = $state<SearchResult[]>([]);
	let total = $state(0);
	let loading = $state(false);
	let searched = $state(false);
	let error = $state('');
	let currentPage = $state(0);
	const pageSize = 20;

	// Filters
	let departments = $state<Department[]>([]);
	let selectedDeptId = $state('');
	let selectedStatuses = $state<Set<string>>(new Set());
	let dateFrom = $state('');
	let dateTo = $state('');

	const statuses = ['DRAFT', 'IN_REVIEW', 'PUBLISHED', 'ARCHIVED'];

	onMount(async () => {
		// Load departments for filter
		try {
			const res = await api.departments.list(0, 100);
			departments = res.data;
		} catch { /* ignore */ }

		// Check URL for initial query
		const q = page.url?.searchParams.get('q');
		if (q) {
			query = q;
			doSearch();
		}
	});

	async function doSearch() {
		if (!query.trim()) return;
		loading = true;
		error = '';
		searched = true;
		try {
			const res = await api.search.query({
				query: query.trim(),
				filters: {
					departmentId: selectedDeptId || undefined,
					status: selectedStatuses.size > 0 ? [...selectedStatuses] : undefined,
					dateFrom: dateFrom || undefined,
					dateTo: dateTo || undefined
				},
				page: currentPage,
				size: pageSize
			});
			results = res.data;
			total = res.meta?.total ?? results.length;
		} catch (e: any) {
			// Search API may not exist yet
			if (e.message?.includes('404')) {
				results = [];
				total = 0;
			} else {
				error = e.message;
			}
		} finally {
			loading = false;
		}
	}

	function handleSubmit(e: Event) {
		e.preventDefault();
		currentPage = 0;
		doSearch();
		// Update URL without navigation
		const url = new URL(window.location.href);
		url.searchParams.set('q', query);
		goto(url.pathname + url.search, { replaceState: true, noScroll: true });
	}

	function toggleStatus(status: string) {
		const next = new Set(selectedStatuses);
		if (next.has(status)) next.delete(status);
		else next.add(status);
		selectedStatuses = next;
	}

	function clearFilters() {
		selectedDeptId = '';
		selectedStatuses = new Set();
		dateFrom = '';
		dateTo = '';
	}

	const hasFilters = $derived(
		selectedDeptId !== '' || selectedStatuses.size > 0 || dateFrom !== '' || dateTo !== ''
	);
</script>

<svelte:head>
	<title>{m.page_title_search()} - {m.nav_app_title()}</title>
</svelte:head>

<PageHeader title={m.page_title_search()} />

<!-- Search bar -->
<form onsubmit={handleSubmit} role="search" class="mt-4">
	<div class="flex gap-2">
		<label for="search-input" class="sr-only">{m.page_title_search()}</label>
		<input
			id="search-input"
			type="search"
			bind:value={query}
			placeholder={m.search_placeholder()}
			class="flex-1 rounded-lg border border-border bg-background px-4 py-3 text-base placeholder:text-muted-foreground focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
		/>
		<button
			type="submit"
			disabled={loading || !query.trim()}
			class="rounded-lg bg-primary px-6 py-3 font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
		>
			{m.btn_search()}
		</button>
	</div>
</form>

{#if searched}
	<div class="mt-6 grid gap-6 lg:grid-cols-4">
		<!-- Sidebar: Faceted filters -->
		<aside class="lg:col-span-1" aria-label="Search filters">
			<div class="space-y-5 rounded-lg border border-border bg-card p-4">
				<div class="flex items-center justify-between">
					<h2 class="text-sm font-semibold">{m.search_filters_heading()}</h2>
					{#if hasFilters}
						<button
							onclick={clearFilters}
							class="text-xs text-primary hover:underline focus:outline-2 focus:outline-ring"
						>
							{m.search_clear_all()}
						</button>
					{/if}
				</div>

				<!-- Department filter -->
				<fieldset>
					<legend class="text-sm font-medium text-muted-foreground">{m.search_filter_department()}</legend>
					<select
						bind:value={selectedDeptId}
						onchange={() => { currentPage = 0; doSearch(); }}
						class="mt-1 w-full rounded-md border border-border bg-background px-2 py-1.5 text-sm focus:outline-2 focus:outline-ring"
						aria-label={m.label_filter_by_department()}
					>
						<option value="">{m.label_all_departments()}</option>
						{#each departments as dept}
							<option value={dept.id}>{dept.name}</option>
						{/each}
					</select>
				</fieldset>

				<!-- Status filter -->
				<fieldset>
					<legend class="text-sm font-medium text-muted-foreground">{m.search_filter_status()}</legend>
					<div class="mt-1 space-y-1">
						{#each statuses as status}
							<label class="flex items-center gap-2 text-sm">
								<input
									type="checkbox"
									checked={selectedStatuses.has(status)}
									onchange={() => { toggleStatus(status); currentPage = 0; doSearch(); }}
									class="rounded focus:ring-ring"
								/>
								{status.replace('_', ' ')}
							</label>
						{/each}
					</div>
				</fieldset>

				<!-- Date range filter -->
				<fieldset>
					<legend class="text-sm font-medium text-muted-foreground">{m.search_filter_date_range()}</legend>
					<div class="mt-1 space-y-2">
						<div>
							<label for="date-from" class="text-xs text-muted-foreground">{m.search_filter_from()}</label>
							<input
								id="date-from"
								type="date"
								bind:value={dateFrom}
								onchange={() => { currentPage = 0; doSearch(); }}
								class="w-full rounded-md border border-border bg-background px-2 py-1 text-sm focus:outline-2 focus:outline-ring"
							/>
						</div>
						<div>
							<label for="date-to" class="text-xs text-muted-foreground">{m.search_filter_to()}</label>
							<input
								id="date-to"
								type="date"
								bind:value={dateTo}
								onchange={() => { currentPage = 0; doSearch(); }}
								class="w-full rounded-md border border-border bg-background px-2 py-1 text-sm focus:outline-2 focus:outline-ring"
							/>
						</div>
					</div>
				</fieldset>
			</div>
		</aside>

		<!-- Results -->
		<div class="lg:col-span-3">
			{#if loading}
				<p aria-live="polite" class="text-muted-foreground">{m.search_searching()}</p>
			{:else if error}
				<div role="alert" class="rounded-md border border-destructive bg-destructive/10 p-4 text-sm text-destructive">
					{error}
				</div>
			{:else if results.length === 0}
				<p class="text-muted-foreground">{m.search_no_results({ query })}</p>
			{:else}
				<p class="mb-4 text-sm text-muted-foreground" aria-live="polite">
					{m.search_result_count({ total: String(total), query })}
				</p>

				<div class="space-y-3">
					{#each results as result}
						<article class="rounded-lg border border-border bg-card p-4 transition hover:border-primary">
							<div class="flex items-start justify-between">
								<div>
									<a
										href="/documents/{result.id}"
										class="text-base font-medium text-primary hover:underline focus:outline-2 focus:outline-offset-2 focus:outline-ring"
									>
										{result.title}
									</a>
									<p class="mt-0.5 text-xs text-muted-foreground">
										{result.departmentName}
										{#if result.docNumber} &middot; #{result.docNumber}{/if}
									</p>
								</div>
								<div class="flex items-center gap-2">
									<StatusBadge status={result.status} />
									<span
										class="text-xs text-muted-foreground"
										aria-label="Relevance: {Math.round(result.relevance * 100)}%"
									>
										{Math.round(result.relevance * 100)}%
									</span>
								</div>
							</div>

							{#if result.snippet}
								<p class="mt-2 text-sm text-muted-foreground line-clamp-2">{result.snippet}</p>
							{/if}

							{#if result.taxonomyTerms.length > 0}
								<div class="mt-2 flex flex-wrap gap-1">
									{#each result.taxonomyTerms as term}
										<span class="rounded bg-muted px-1.5 py-0.5 text-xs text-muted-foreground">{term}</span>
									{/each}
								</div>
							{/if}

							<p class="mt-2 text-xs text-muted-foreground">
								{new Date(result.createdAt).toLocaleDateString()}
							</p>
						</article>
					{/each}
				</div>

				<!-- Pagination -->
				{#if total > pageSize}
					<nav aria-label="Search results pagination" class="mt-4 flex items-center justify-between text-sm">
						<p class="text-muted-foreground">
							{m.search_showing({ from: String(currentPage * pageSize + 1), to: String(Math.min((currentPage + 1) * pageSize, total)), total: String(total) })}
						</p>
						<div class="flex gap-2">
							<button
								onclick={() => { currentPage--; doSearch(); }}
								disabled={currentPage === 0}
								class="rounded-md border border-border px-3 py-1 hover:bg-muted focus:outline-2 focus:outline-ring disabled:opacity-50 disabled:cursor-not-allowed"
							>
								{m.btn_previous()}
							</button>
							<button
								onclick={() => { currentPage++; doSearch(); }}
								disabled={(currentPage + 1) * pageSize >= total}
								class="rounded-md border border-border px-3 py-1 hover:bg-muted focus:outline-2 focus:outline-ring disabled:opacity-50 disabled:cursor-not-allowed"
							>
								{m.btn_next()}
							</button>
						</div>
					</nav>
				{/if}
			{/if}
		</div>
	</div>
{:else}
	<p class="mt-6 text-sm text-muted-foreground">{m.search_empty_hint()}</p>
{/if}
