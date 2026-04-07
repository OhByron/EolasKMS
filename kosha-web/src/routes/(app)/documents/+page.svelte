<script lang="ts">
	import { onMount } from 'svelte';
	import { api } from '$lib/api';
	import type { Department, DocumentListItem } from '$lib/types/api';
	import PageHeader from '$lib/components/kosha/PageHeader.svelte';
	import StatusBadge from '$lib/components/kosha/StatusBadge.svelte';
	import * as m from '$paraglide/messages';

	let documents = $state<DocumentListItem[]>([]);
	let total = $state(0);
	let loading = $state(true);
	let error = $state('');
	let currentPage = $state(0);
	const pageSize = 20;

	// Department filter state. Empty string = all departments; bound directly
	// to the <select> and converted to undefined when calling the API.
	let departments = $state<Department[]>([]);
	let departmentFilter = $state('');

	onMount(async () => {
		// Load departments for the filter dropdown; failure is non-blocking.
		try {
			const res = await api.departments.list(0, 200);
			departments = res.data;
		} catch {
			// Filter just won't populate — main list still works.
		}
		await loadDocuments();
	});

	async function loadDocuments() {
		loading = true;
		error = '';
		try {
			const res = await api.documents.list(
				currentPage,
				pageSize,
				departmentFilter || undefined,
			);
			documents = res.data;
			total = res.meta?.total ?? 0;
		} catch (e: any) {
			error = e.message;
		} finally {
			loading = false;
		}
	}

	function onDepartmentChange() {
		// Reset to first page whenever the filter changes so we don't land
		// on an empty page past the new total.
		currentPage = 0;
		loadDocuments();
	}

	function prevPage() {
		if (currentPage > 0) {
			currentPage--;
			loadDocuments();
		}
	}

	function nextPage() {
		if ((currentPage + 1) * pageSize < total) {
			currentPage++;
			loadDocuments();
		}
	}
</script>

<svelte:head>
	<title>{m.doc_title()} - {m.nav_app_title()}</title>
</svelte:head>

<PageHeader title={m.doc_title()} description="{total} document{total !== 1 ? 's' : ''}">
	<a
		href="/documents/upload"
		class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-offset-2 focus:outline-ring"
	>
		{m.btn_upload_document()}
	</a>
</PageHeader>

<div class="mt-4 flex flex-wrap items-end gap-3">
	<div class="flex flex-col gap-1">
		<label for="doc-dept-filter" class="text-xs font-medium text-muted-foreground">
			{m.label_filter_by_department()}
		</label>
		<select
			id="doc-dept-filter"
			bind:value={departmentFilter}
			onchange={onDepartmentChange}
			class="rounded-md border border-border bg-background px-3 py-1.5 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
		>
			<option value="">{m.label_all_departments()}</option>
			{#each departments as d (d.id)}
				<option value={d.id}>{d.name}</option>
			{/each}
		</select>
	</div>
	{#if departmentFilter}
		<button
			type="button"
			onclick={() => { departmentFilter = ''; onDepartmentChange(); }}
			class="rounded-md border border-border px-3 py-1.5 text-xs font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring"
		>
			{m.btn_clear_filter()}
		</button>
	{/if}
</div>

{#if loading}
	<p aria-live="polite" class="mt-4 text-muted-foreground">{m.doc_loading()}</p>
{:else if error}
	<div role="alert" class="mt-4 rounded-md border border-destructive bg-destructive/10 p-4">
		<p class="text-sm text-destructive">{error}</p>
		<button
			onclick={loadDocuments}
			class="mt-2 text-sm font-medium text-destructive underline focus:outline-2 focus:outline-ring"
		>
			{m.btn_retry()}
		</button>
	</div>
{:else if documents.length === 0}
	<div class="mt-8 text-center">
		<p class="text-muted-foreground">{m.doc_no_documents()}</p>
		<a href="/documents/upload" class="mt-2 inline-block text-sm font-medium text-primary underline">
			{m.doc_upload_first()}
		</a>
	</div>
{:else}
	<div class="mt-4 overflow-x-auto rounded-lg border border-border">
		<table class="w-full text-sm" aria-label="Document list">
			<thead>
				<tr class="border-b border-border bg-muted/50">
					<th scope="col" class="px-4 py-3 text-left font-semibold">{m.label_title()}</th>
					<th scope="col" class="px-4 py-3 text-left font-semibold">{m.label_department()}</th>
					<th scope="col" class="px-4 py-3 text-left font-semibold">{m.label_status()}</th>
					<th scope="col" class="px-4 py-3 text-left font-semibold">{m.label_current_version()}</th>
					<th scope="col" class="px-4 py-3 text-left font-semibold">{m.label_created()}</th>
				</tr>
			</thead>
			<tbody>
				{#each documents as doc}
					<tr class="border-b border-border transition hover:bg-muted/30">
						<td class="px-4 py-3">
							<a
								href="/documents/{doc.id}"
								class="font-medium text-primary hover:underline focus:outline-2 focus:outline-offset-2 focus:outline-ring"
							>
								{doc.title}
							</a>
						</td>
						<td class="px-4 py-3 text-muted-foreground">{doc.departmentName}</td>
						<td class="px-4 py-3">
							<StatusBadge status={doc.status} />
							{#if doc.checkedOut}
								<StatusBadge status="LOCKED" />
							{/if}
						</td>
						<td class="px-4 py-3 text-muted-foreground">{doc.currentVersion ?? '-'}</td>
						<td class="px-4 py-3 text-muted-foreground">
							{new Date(doc.createdAt).toLocaleDateString()}
						</td>
					</tr>
				{/each}
			</tbody>
		</table>
	</div>

	<!-- Pagination -->
	<nav aria-label="Document list pagination" class="mt-4 flex items-center justify-between text-sm">
		<p class="text-muted-foreground">
			Showing {currentPage * pageSize + 1}-{Math.min((currentPage + 1) * pageSize, total)} of {total}
		</p>
		<div class="flex gap-2">
			<button
				onclick={prevPage}
				disabled={currentPage === 0}
				class="rounded-md border border-border px-3 py-1 hover:bg-muted focus:outline-2 focus:outline-ring disabled:opacity-50 disabled:cursor-not-allowed"
			>
				{m.btn_previous()}
			</button>
			<button
				onclick={nextPage}
				disabled={(currentPage + 1) * pageSize >= total}
				class="rounded-md border border-border px-3 py-1 hover:bg-muted focus:outline-2 focus:outline-ring disabled:opacity-50 disabled:cursor-not-allowed"
			>
				{m.btn_next()}
			</button>
		</div>
	</nav>
{/if}
