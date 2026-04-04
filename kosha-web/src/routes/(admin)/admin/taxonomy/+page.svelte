<script lang="ts">
	import { onMount } from 'svelte';
	import { api } from '$lib/api';
	import type { TaxonomyTreeNode, TaxonomyTerm } from '$lib/types/api';
	import PageHeader from '$lib/components/kosha/PageHeader.svelte';
	import TreeView from '$lib/components/kosha/TreeView.svelte';
	import StatusBadge from '$lib/components/kosha/StatusBadge.svelte';
	import ErrorBoundary from '$lib/components/kosha/ErrorBoundary.svelte';

	let treeNodes = $state<TaxonomyTreeNode[]>([]);
	let selectedTermId = $state('');
	let selectedTerm = $state<TaxonomyTerm | null>(null);
	let loading = $state(true);
	let error = $state('');
	let detailLoading = $state(false);

	// Create term state
	let showCreate = $state(false);
	let newLabel = $state('');
	let newDesc = $state('');
	let newSource = $state('MANUAL');
	let creating = $state(false);
	let duplicateWarning = $state('');

	// Edit term state
	let editing = $state(false);
	let editLabel = $state('');
	let editDesc = $state('');
	let saving = $state(false);

	// Import seed state
	let showImport = $state(false);

	onMount(() => loadTree());

	async function loadTree() {
		loading = true;
		error = '';
		try {
			const res = await api.taxonomy.tree();
			treeNodes = res.data;
		} catch (e: any) {
			if (e.message?.includes('404')) treeNodes = [];
			else error = e.message;
		} finally {
			loading = false;
		}
	}

	async function handleSelect(termId: string) {
		selectedTermId = termId;
		detailLoading = true;
		try {
			const res = await api.taxonomy.get(termId);
			selectedTerm = res.data;
		} catch {
			selectedTerm = null;
		} finally {
			detailLoading = false;
		}
	}

	async function approveTerm(id: string) {
		try {
			await api.post(`/api/v1/taxonomy/candidates/${id}/approve`, {});
			await loadTree();
			if (selectedTermId === id) await handleSelect(id);
			error = '';
		} catch (e: any) {
			error = e.message;
		}
	}

	async function rejectTerm(id: string) {
		try {
			await api.post(`/api/v1/taxonomy/candidates/${id}/reject`, {});
			selectedTermId = '';
			selectedTerm = null;
			await loadTree();
			error = '';
		} catch (e: any) {
			error = e.message;
		}
	}

	let aiDefining = $state(false);

	// Suggest children state
	interface SuggestedTerm { label: string; description: string; }
	let suggestedChildren = $state<SuggestedTerm[]>([]);
	let suggesting = $state(false);
	let showSuggestions = $state(false);

	async function aiDefine(label: string, target: 'edit' | 'create') {
		aiDefining = true;
		try {
			// Pass parent context if adding a child term
			const body: Record<string, string> = { label };
			if (selectedTerm && target === 'create') {
				body.parentLabel = selectedTerm.label;
				if (selectedTerm.description) body.parentDescription = selectedTerm.description;
			}
			const res = await api.post<{ definition: string }>('/api/v1/admin/ai/define-term', body);
			const def = res.data.definition;
			if (target === 'edit') editDesc = def;
			else newDesc = def;
		} catch (e: any) {
			error = e.message;
		} finally {
			aiDefining = false;
		}
	}

	async function suggestChildren() {
		if (!selectedTerm) return;
		suggesting = true;
		showSuggestions = true;
		suggestedChildren = [];
		try {
			// Get existing children from the tree
			const node = findNodeInTree(treeNodes, selectedTerm.id);
			const existingChildren = node?.children.map((c: any) => c.term.label) ?? [];

			const res = await api.post<SuggestedTerm[]>('/api/v1/admin/ai/suggest-children', {
				parentLabel: selectedTerm.label,
				parentDescription: selectedTerm.description || undefined,
				existingChildren,
			});
			suggestedChildren = res.data;
		} catch (e: any) {
			error = e.message;
		} finally {
			suggesting = false;
		}
	}

	function findNodeInTree(nodes: any[], id: string): any | null {
		for (const node of nodes) {
			if (node.term.id === id) return node;
			const found = findNodeInTree(node.children, id);
			if (found) return found;
		}
		return null;
	}

	async function addSuggestedChild(suggestion: SuggestedTerm) {
		if (!selectedTerm) return;
		try {
			await api.post('/api/v1/taxonomy/terms', {
				label: suggestion.label,
				description: suggestion.description,
				source: 'AI_GENERATED',
				parentTermId: selectedTerm.id,
			});
			// Remove from suggestions list
			suggestedChildren = suggestedChildren.filter(s => s.label !== suggestion.label);
			await loadTree();
		} catch (e: any) {
			error = e.message;
		}
	}

	function startEditing() {
		if (!selectedTerm) return;
		editLabel = selectedTerm.label;
		editDesc = selectedTerm.description ?? '';
		editing = true;
		duplicateWarning = '';
	}

	async function saveEdit() {
		if (!selectedTerm) return;
		saving = true;
		error = '';
		duplicateWarning = '';
		try {
			// Check for duplicate if label changed
			if (editLabel.trim() !== selectedTerm.label) {
				const dupRes = await api.get<{ isDuplicate: boolean; existingLabel?: string; existingStatus?: string }>(
					`/api/v1/taxonomy/terms/check-duplicate?label=${encodeURIComponent(editLabel.trim())}`
				);
				if (dupRes.data.isDuplicate) {
					duplicateWarning = `A term "${dupRes.data.existingLabel}" already exists (${dupRes.data.existingStatus}). Choose a different label.`;
					saving = false;
					return;
				}
			}

			await api.patch(`/api/v1/taxonomy/terms/${selectedTerm.id}`, {
				label: editLabel.trim(),
				description: editDesc.trim() || null,
			});
			editing = false;
			await loadTree();
			await handleSelect(selectedTerm.id);
		} catch (e: any) {
			error = e.message;
		} finally {
			saving = false;
		}
	}

	async function checkNewTermDuplicate() {
		if (!newLabel.trim()) { duplicateWarning = ''; return; }
		try {
			const res = await api.get<{ isDuplicate: boolean; existingLabel?: string; existingStatus?: string }>(
				`/api/v1/taxonomy/terms/check-duplicate?label=${encodeURIComponent(newLabel.trim())}`
			);
			if (res.data.isDuplicate) {
				duplicateWarning = `A term "${res.data.existingLabel}" already exists (${res.data.existingStatus}).`;
			} else {
				duplicateWarning = '';
			}
		} catch {
			duplicateWarning = '';
		}
	}

	async function deleteTerm(id: string) {
		if (!confirm('Delete this taxonomy term?')) return;
		try {
			await api.delete(`/api/v1/taxonomy/terms/${id}`);
			selectedTermId = '';
			selectedTerm = null;
			await loadTree();
			error = '';
		} catch (e: any) {
			error = e.message;
		}
	}

	async function createTerm(e: Event) {
		e.preventDefault();
		creating = true;
		try {
			await api.post('/api/v1/taxonomy/terms', {
				label: newLabel,
				description: newDesc || undefined,
				source: newSource,
				parentTermId: selectedTermId || undefined
			});
			showCreate = false;
			newLabel = '';
			newDesc = '';
			await loadTree();
		} catch (e: any) {
			error = e.message;
		} finally {
			creating = false;
		}
	}
</script>

<svelte:head>
	<title>Taxonomy Management - Administration - Kosha</title>
</svelte:head>

<PageHeader title="Taxonomy Management" description="Edit taxonomy tree and import seeds">
	<button onclick={() => (showImport = true)}
		class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-ring">
		Import Seed
	</button>
	<button onclick={() => (showCreate = true)}
		class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-ring">
		+ Add Term
	</button>
</PageHeader>

{#if error}
	<div role="alert" class="mt-4 rounded-md border border-destructive bg-destructive/10 p-3 text-sm text-destructive">{error}</div>
{/if}

{#if loading}
	<p aria-live="polite" class="mt-6 text-muted-foreground">Loading taxonomy...</p>
{:else}
	<div class="mt-6 grid gap-6 lg:grid-cols-3">
		<!-- Tree -->
		<div class="lg:col-span-1">
			{#if treeNodes.length === 0}
				<div class="rounded-lg border border-dashed border-border p-8 text-center">
					<p class="text-muted-foreground">No taxonomy terms yet.</p>
					<p class="mt-1 text-sm text-muted-foreground">Import a seed taxonomy or create terms manually.</p>
				</div>
			{:else}
				<div class="rounded-lg border border-border bg-card p-3 max-h-[calc(100vh-220px)] overflow-y-auto">
					<TreeView nodes={treeNodes} bind:selectedId={selectedTermId} onSelect={handleSelect} />
				</div>
			{/if}
		</div>

		<!-- Detail / Edit panel -->
		<div class="lg:col-span-2">
			{#if !selectedTermId}
				<div class="flex h-full items-center justify-center rounded-lg border border-dashed border-border p-12">
					<p class="text-muted-foreground">Select a term to view or edit.</p>
				</div>
			{:else if detailLoading}
				<p aria-live="polite" class="text-muted-foreground">Loading...</p>
			{:else if selectedTerm}
				<div class="space-y-4">
					<section class="rounded-lg border border-border bg-card p-5">
						{#if editing}
							<!-- Edit mode -->
							<h2 class="text-lg font-semibold">Edit Term</h2>
							<form onsubmit={(e) => { e.preventDefault(); saveEdit(); }} class="mt-3 space-y-4">
								<div>
									<label for="edit-label" class="block text-sm font-medium">Label</label>
									<input id="edit-label" type="text" bind:value={editLabel} required maxlength="500"
										class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:outline-2 focus:outline-ring" />
								</div>
								<div>
									<div class="flex items-center justify-between">
										<label for="edit-desc" class="block text-sm font-medium">Description / Definition</label>
										<button type="button" onclick={() => aiDefine(editLabel, 'edit')} disabled={aiDefining || !editLabel.trim()}
											class="text-xs font-medium text-primary hover:underline focus:outline-2 focus:outline-ring disabled:opacity-50">
											{aiDefining ? 'Generating...' : 'AI Assist'}
										</button>
									</div>
									<textarea id="edit-desc" bind:value={editDesc} rows="4" maxlength="2000"
										placeholder="Provide a clear definition for this term..."
										class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:outline-2 focus:outline-ring"></textarea>
								</div>
								{#if duplicateWarning}
									<div role="alert" class="rounded-md border border-warning bg-warning/10 p-3 text-sm text-warning">
										{duplicateWarning}
									</div>
								{/if}
								<div class="flex gap-3">
									<button type="button" onclick={() => { editing = false; duplicateWarning = ''; }}
										class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-ring">
										Cancel
									</button>
									<button type="submit" disabled={saving || !editLabel.trim()}
										class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-ring disabled:opacity-50">
										{saving ? 'Saving...' : 'Save Changes'}
									</button>
								</div>
							</form>
						{:else}
							<!-- View mode -->
							<div class="flex items-start justify-between">
								<div>
									<h2 class="text-xl font-semibold">{selectedTerm.label}</h2>
									<div class="mt-1 flex items-center gap-2">
										<StatusBadge status={selectedTerm.status} />
										<span class="rounded-md bg-muted px-2 py-0.5 text-xs text-muted-foreground">
											{selectedTerm.source.replace('_', ' ')}
										</span>
									</div>
								</div>
								<div class="flex gap-2">
									<button onclick={startEditing}
										class="rounded-md border border-border px-3 py-1 text-sm hover:bg-muted focus:outline-2 focus:outline-ring">
										Edit
									</button>
									<button onclick={() => deleteTerm(selectedTerm!.id)}
										class="rounded-md border border-destructive/50 px-3 py-1 text-sm text-destructive hover:bg-destructive/10 focus:outline-2 focus:outline-ring">
										Delete
									</button>
									{#if selectedTerm.status === 'CANDIDATE'}
										<button onclick={() => approveTerm(selectedTerm!.id)}
											class="rounded-md bg-success px-3 py-1 text-sm text-white hover:opacity-90 focus:outline-2 focus:outline-ring">
											Approve
										</button>
										<button onclick={() => rejectTerm(selectedTerm!.id)}
											class="rounded-md bg-destructive px-3 py-1 text-sm text-white hover:opacity-90 focus:outline-2 focus:outline-ring">
											Reject
										</button>
									{/if}
								</div>
							</div>
							{#if selectedTerm.description}
								<p class="mt-3 text-sm leading-relaxed">{selectedTerm.description}</p>
							{:else}
								<p class="mt-3 text-sm italic text-muted-foreground">No description. Click Edit to add one.</p>
							{/if}
						{/if}
						<dl class="mt-4 grid grid-cols-2 gap-2 text-sm">
							<div>
								<dt class="text-muted-foreground">Normalized</dt>
								<dd class="font-mono text-xs">{selectedTerm.normalizedLabel}</dd>
							</div>
							{#if selectedTerm.sourceRef}
								<div>
									<dt class="text-muted-foreground">Source Ref</dt>
									<dd class="font-mono text-xs">{selectedTerm.sourceRef}</dd>
								</div>
							{/if}
							<div>
								<dt class="text-muted-foreground">Created</dt>
								<dd>{new Date(selectedTerm.createdAt).toLocaleDateString()}</dd>
							</div>
						</dl>
					</section>

					<!-- Add child term -->
					<section class="rounded-lg border border-border bg-card p-5">
						<div class="flex items-center gap-4">
							<button
								onclick={() => { showCreate = true; showSuggestions = false; }}
								class="text-sm text-primary hover:underline focus:outline-2 focus:outline-ring"
							>
								+ Add child term manually
							</button>
							<button
								onclick={suggestChildren}
								disabled={suggesting}
								class="text-sm font-medium text-accent-foreground bg-accent/20 rounded-md px-3 py-1 hover:bg-accent/30 focus:outline-2 focus:outline-ring disabled:opacity-50"
							>
								{suggesting ? 'Thinking...' : 'AI Suggest Children'}
							</button>
						</div>

						{#if showSuggestions}
							<div class="mt-4">
								{#if suggesting}
									<p class="text-sm text-muted-foreground" aria-live="polite">Generating suggestions for "{selectedTerm.label}"...</p>
								{:else if suggestedChildren.length === 0}
									<p class="text-sm text-muted-foreground">No suggestions generated.</p>
								{:else}
									<h3 class="text-sm font-semibold text-muted-foreground">Suggested child terms</h3>
									<p class="text-xs text-muted-foreground">Click Add to create the term under "{selectedTerm.label}"</p>
									<ul class="mt-2 space-y-2">
										{#each suggestedChildren as suggestion}
											<li class="flex items-start justify-between gap-3 rounded-md border border-border p-3">
												<div class="flex-1">
													<p class="font-medium text-sm">{suggestion.label}</p>
													<p class="text-xs text-muted-foreground mt-0.5">{suggestion.description}</p>
												</div>
												<button
													onclick={() => addSuggestedChild(suggestion)}
													class="shrink-0 rounded-md bg-success px-3 py-1 text-xs font-medium text-white hover:opacity-90 focus:outline-2 focus:outline-ring"
												>
													Add
												</button>
											</li>
										{/each}
									</ul>
								{/if}
							</div>
						{/if}
					</section>
				</div>
			{/if}
		</div>
	</div>
{/if}

<!-- Create term dialog -->
{#if showCreate}
	<!-- svelte-ignore a11y_no_static_element_interactions -->
	<div class="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
		onclick={(e) => { if (e.target === e.currentTarget) showCreate = false; }}
		onkeydown={(e) => { if (e.key === 'Escape') showCreate = false; }}>
		<div class="w-full max-w-md rounded-lg border border-border bg-card p-6 shadow-lg" role="dialog" aria-modal="true" aria-label="Add taxonomy term">
			<h2 class="text-lg font-semibold">Add Taxonomy Term</h2>
			{#if selectedTerm}
				<p class="text-sm text-muted-foreground">Parent: {selectedTerm.label}</p>
			{/if}
			<form onsubmit={createTerm} class="mt-4 space-y-4">
				<div>
					<label for="term-label" class="block text-sm font-medium">Label <span class="text-destructive">*</span></label>
					<input id="term-label" type="text" bind:value={newLabel} required maxlength="500"
						onblur={checkNewTermDuplicate}
						class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:outline-2 focus:outline-ring" />
				</div>
				{#if duplicateWarning}
					<div role="alert" class="rounded-md border border-warning bg-warning/10 p-3 text-sm text-warning">
						{duplicateWarning}
					</div>
				{/if}
				<div>
					<div class="flex items-center justify-between">
						<label for="term-desc" class="block text-sm font-medium">Description / Definition <span class="text-destructive">*</span></label>
						<button type="button" onclick={() => aiDefine(newLabel, 'create')} disabled={aiDefining || !newLabel.trim()}
							class="text-xs font-medium text-primary hover:underline focus:outline-2 focus:outline-ring disabled:opacity-50">
							{aiDefining ? 'Generating...' : 'AI Assist'}
						</button>
					</div>
					<textarea id="term-desc" bind:value={newDesc} rows="3" required
						placeholder="Provide a clear definition for this term..."
						class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:outline-2 focus:outline-ring"></textarea>
				</div>
				<div class="flex justify-end gap-3 pt-2">
					<button type="button" onclick={() => { showCreate = false; duplicateWarning = ''; }}
						class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-ring">Cancel</button>
					<button type="submit" disabled={!newLabel.trim() || !newDesc.trim() || creating || !!duplicateWarning}
						class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-ring disabled:opacity-50">
						{creating ? 'Creating...' : 'Add Term'}
					</button>
				</div>
			</form>
		</div>
	</div>
{/if}

<!-- Import seed dialog -->
{#if showImport}
	<!-- svelte-ignore a11y_no_static_element_interactions -->
	<div class="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
		onclick={(e) => { if (e.target === e.currentTarget) showImport = false; }}
		onkeydown={(e) => { if (e.key === 'Escape') showImport = false; }}>
		<div class="w-full max-w-md rounded-lg border border-border bg-card p-6 shadow-lg" role="dialog" aria-modal="true" aria-label="Import seed taxonomy">
			<h2 class="text-lg font-semibold">Import Seed Taxonomy</h2>
			<p class="mt-2 text-sm text-muted-foreground">Upload a taxonomy file (SKOS/RDF, CSV, or JSON) to seed the taxonomy tree.</p>
			<div class="mt-4">
				<label class="block cursor-pointer rounded-lg border-2 border-dashed border-border p-8 text-center hover:border-primary">
					<p class="text-sm font-medium">Choose taxonomy file</p>
					<p class="text-xs text-muted-foreground">SKOS, CSV, or JSON format</p>
					<input type="file" class="sr-only" accept=".json,.csv,.rdf,.xml" />
				</label>
			</div>
			<div class="mt-4 flex justify-end gap-3">
				<button onclick={() => (showImport = false)}
					class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-ring">Cancel</button>
				<button disabled
					class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground disabled:opacity-50">Import</button>
			</div>
		</div>
	</div>
{/if}
