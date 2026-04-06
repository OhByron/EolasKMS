<script lang="ts">
	import { onMount } from 'svelte';
	import { api } from '$lib/api';
	import type { TaxonomyTreeNode, TaxonomyTerm, DocumentListItem } from '$lib/types/api';
	import PageHeader from '$lib/components/kosha/PageHeader.svelte';
	import TreeView from '$lib/components/kosha/TreeView.svelte';
	import StatusBadge from '$lib/components/kosha/StatusBadge.svelte';
	import ErrorBoundary from '$lib/components/kosha/ErrorBoundary.svelte';
	import * as m from '$paraglide/messages';

	let treeNodes = $state<TaxonomyTreeNode[]>([]);
	let selectedTermId = $state('');
	let selectedTerm = $state<TaxonomyTerm | null>(null);
	let termDocuments = $state<DocumentListItem[]>([]);
	let termDocTotal = $state(0);
	let loading = $state(true);
	let detailLoading = $state(false);
	let error = $state('');

	onMount(() => loadTree());

	async function loadTree() {
		loading = true;
		error = '';
		try {
			const res = await api.taxonomy.tree();
			treeNodes = res.data;
		} catch (e: any) {
			if (e.message?.includes('404')) {
				treeNodes = [];
			} else {
				error = e.message;
			}
		} finally {
			loading = false;
		}
	}

	async function handleSelect(termId: string) {
		selectedTermId = termId;
		detailLoading = true;
		try {
			const [termRes, docsRes] = await Promise.all([
				api.taxonomy.get(termId),
				api.taxonomy.documents(termId, 0, 10)
			]);
			selectedTerm = termRes.data;
			termDocuments = docsRes.data;
			termDocTotal = docsRes.meta?.total ?? docsRes.data.length;
		} catch {
			selectedTerm = null;
			termDocuments = [];
			termDocTotal = 0;
		} finally {
			detailLoading = false;
		}
	}
</script>

<svelte:head>
	<title>{m.page_title_taxonomy()} - {m.nav_app_title()}</title>
</svelte:head>

<PageHeader title={m.page_title_taxonomy()} description={m.dashboard_taxonomy_desc()} />

{#if loading}
	<p aria-live="polite" class="mt-6 text-muted-foreground">{m.taxonomy_loading()}</p>
{:else if error}
	<div class="mt-6">
		<ErrorBoundary {error} onRetry={loadTree} />
	</div>
{:else if treeNodes.length === 0}
	<div class="mt-8 text-center">
		<p class="text-lg text-muted-foreground">{m.taxonomy_no_terms()}</p>
		<p class="mt-1 text-sm text-muted-foreground">
			{m.taxonomy_admin_hint()}
		</p>
	</div>
{:else}
	<div class="mt-6 grid gap-6 lg:grid-cols-3">
		<!-- Left: Tree -->
		<div class="lg:col-span-1">
			<div class="rounded-lg border border-border bg-card p-3 max-h-[calc(100vh-220px)] overflow-y-auto">
				<TreeView
					nodes={treeNodes}
					bind:selectedId={selectedTermId}
					onSelect={handleSelect}
				/>
			</div>
		</div>

		<!-- Right: Detail panel -->
		<div class="lg:col-span-2">
			{#if !selectedTermId}
				<div class="flex h-full items-center justify-center rounded-lg border border-dashed border-border p-12">
					<p class="text-muted-foreground">{m.taxonomy_select_hint()}</p>
				</div>
			{:else if detailLoading}
				<p aria-live="polite" class="text-muted-foreground">{m.taxonomy_loading_details()}</p>
			{:else if selectedTerm}
				<div class="space-y-4">
					<!-- Term header -->
					<section class="rounded-lg border border-border bg-card p-5">
						<div class="flex items-start justify-between">
							<div>
								<h2 class="text-xl font-semibold">{selectedTerm.label}</h2>
								<div class="mt-1 flex items-center gap-2">
									<StatusBadge status={selectedTerm.status} />
									<span class="text-xs text-muted-foreground">
										{m.label_source()}: {selectedTerm.source.replace('_', ' ')}
									</span>
								</div>
							</div>
							<span class="rounded-md bg-muted px-3 py-1 text-sm font-medium">
								{termDocTotal} document{termDocTotal !== 1 ? 's' : ''}
							</span>
						</div>
						{#if selectedTerm.description}
							<p class="mt-3 text-sm leading-relaxed">{selectedTerm.description}</p>
						{/if}
					</section>

					<!-- Documents in this term -->
					<section class="rounded-lg border border-border bg-card p-5">
						<h3 class="text-sm font-semibold text-muted-foreground">{m.taxonomy_documents_heading()}</h3>
						{#if termDocuments.length === 0}
							<p class="mt-2 text-sm text-muted-foreground">{m.taxonomy_no_documents()}</p>
						{:else}
							<ul class="mt-3 space-y-2">
								{#each termDocuments as doc}
									<li class="flex items-center justify-between rounded-md border border-border px-3 py-2 text-sm">
										<a
											href="/documents/{doc.id}"
											class="font-medium text-primary hover:underline focus:outline-2 focus:outline-ring"
										>
											{doc.title}
										</a>
										<div class="flex items-center gap-2">
											<StatusBadge status={doc.status} />
											<span class="text-xs text-muted-foreground">
												{new Date(doc.createdAt).toLocaleDateString()}
											</span>
										</div>
									</li>
								{/each}
							</ul>
							{#if termDocTotal > 10}
								<a
									href="/search?taxonomy={selectedTermId}"
									class="mt-3 inline-block text-sm text-primary hover:underline focus:outline-2 focus:outline-ring"
								>
									{m.taxonomy_view_all_documents({ count: String(termDocTotal) })}
								</a>
							{/if}
						{/if}
					</section>
				</div>
			{/if}
		</div>
	</div>
{/if}
