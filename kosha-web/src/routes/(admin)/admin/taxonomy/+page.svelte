<script lang="ts">
	import { onMount } from 'svelte';
	import { api } from '$lib/api';
	import type { TaxonomyTreeNode, TaxonomyTerm, TaxonomyImportPreview, TaxonomyImportResult } from '$lib/types/api';
	import PageHeader from '$lib/components/kosha/PageHeader.svelte';
	import TreeView from '$lib/components/kosha/TreeView.svelte';
	import StatusBadge from '$lib/components/kosha/StatusBadge.svelte';
	import ErrorBoundary from '$lib/components/kosha/ErrorBoundary.svelte';
	import * as m from '$paraglide/messages';

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

	// Import state
	let showImport = $state(false);
	let importFileContent = $state('');
	let importFileName = $state('');
	let importFormat = $state('');
	let importSourceRef = $state('');
	let importPreviewing = $state(false);
	let importCommitting = $state(false);
	let importPreview = $state<TaxonomyImportPreview | null>(null);
	let importResult = $state<TaxonomyImportResult | null>(null);
	let importError = $state('');

	function detectFormat(filename: string): string {
		const ext = filename.split('.').pop()?.toLowerCase() ?? '';
		if (ext === 'csv') return 'csv';
		if (ext === 'json') return 'json';
		if (ext === 'xml' || ext === 'rdf') return 'xml';
		return 'csv';
	}

	function onImportFilePick(ev: Event) {
		const input = ev.target as HTMLInputElement;
		const file = input.files?.[0];
		if (!file) return;
		importFileName = file.name;
		importFormat = detectFormat(file.name);
		importPreview = null;
		importResult = null;
		importError = '';
		const reader = new FileReader();
		reader.onload = () => { importFileContent = String(reader.result ?? ''); };
		reader.readAsText(file);
	}

	async function previewImport() {
		if (!importFileContent.trim()) { importError = 'No file content to preview.'; return; }
		importPreviewing = true;
		importError = '';
		importPreview = null;
		try {
			const res = await api.taxonomy.importPreview(importFileContent, importFormat);
			importPreview = res.data;
		} catch (e: any) {
			importError = e.message ?? 'Preview failed.';
		} finally {
			importPreviewing = false;
		}
	}

	async function commitImport() {
		if (!importFileContent.trim()) return;
		importCommitting = true;
		importError = '';
		try {
			const res = await api.taxonomy.importCommit(importFileContent, importFormat, importSourceRef || undefined);
			importResult = res.data;
			importPreview = null;
			await loadTree();
		} catch (e: any) {
			importError = e.message ?? 'Import failed.';
		} finally {
			importCommitting = false;
		}
	}

	function resetImportDialog() {
		showImport = false;
		importFileContent = '';
		importFileName = '';
		importFormat = '';
		importSourceRef = '';
		importPreview = null;
		importResult = null;
		importError = '';
	}

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
		if (!confirm(m.taxmgmt_delete_confirm())) return;
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
	<title>{m.page_title_taxonomy_mgmt()} - {m.nav_sidebar_administration()} - {m.nav_app_title()}</title>
</svelte:head>

<PageHeader title={m.taxmgmt_title()} description={m.taxmgmt_desc()}>
	<button onclick={() => (showImport = true)}
		class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-ring">
		{m.taxmgmt_import_seed()}
	</button>
	<button onclick={() => (showCreate = true)}
		class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-ring">
		{m.taxmgmt_add_term()}
	</button>
</PageHeader>

{#if error}
	<div role="alert" class="mt-4 rounded-md border border-destructive bg-destructive/10 p-3 text-sm text-destructive">{error}</div>
{/if}

{#if loading}
	<p aria-live="polite" class="mt-6 text-muted-foreground">{m.taxonomy_loading()}</p>
{:else}
	<div class="mt-6 grid gap-6 lg:grid-cols-3">
		<!-- Tree -->
		<div class="lg:col-span-1">
			{#if treeNodes.length === 0}
				<div class="rounded-lg border border-dashed border-border p-8 text-center">
					<p class="text-muted-foreground">{m.taxmgmt_no_terms()}</p>
					<p class="mt-1 text-sm text-muted-foreground">{m.taxmgmt_no_terms_hint()}</p>
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
					<p class="text-muted-foreground">{m.taxmgmt_select_hint()}</p>
				</div>
			{:else if detailLoading}
				<p aria-live="polite" class="text-muted-foreground">{m.app_loading()}</p>
			{:else if selectedTerm}
				<div class="space-y-4">
					<section class="rounded-lg border border-border bg-card p-5">
						{#if editing}
							<!-- Edit mode -->
							<h2 class="text-lg font-semibold">{m.taxmgmt_edit_term()}</h2>
							<form onsubmit={(e) => { e.preventDefault(); saveEdit(); }} class="mt-3 space-y-4">
								<div>
									<label for="edit-label" class="block text-sm font-medium">{m.taxmgmt_label()}</label>
									<input id="edit-label" type="text" bind:value={editLabel} required maxlength="500"
										class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:outline-2 focus:outline-ring" />
								</div>
								<div>
									<div class="flex items-center justify-between">
										<label for="edit-desc" class="block text-sm font-medium">{m.taxmgmt_desc_definition()}</label>
										<button type="button" onclick={() => aiDefine(editLabel, 'edit')} disabled={aiDefining || !editLabel.trim()}
											class="text-xs font-medium text-primary hover:underline focus:outline-2 focus:outline-ring disabled:opacity-50">
											{aiDefining ? m.taxmgmt_generating() : m.taxmgmt_ai_assist()}
										</button>
									</div>
									<textarea id="edit-desc" bind:value={editDesc} rows="4" maxlength="2000"
										placeholder={m.taxmgmt_desc_placeholder()}
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
										{m.btn_cancel()}
									</button>
									<button type="submit" disabled={saving || !editLabel.trim()}
										class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-ring disabled:opacity-50">
										{saving ? m.btn_saving() : m.btn_save_changes()}
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
										{m.btn_edit()}
									</button>
									<button onclick={() => deleteTerm(selectedTerm!.id)}
										class="rounded-md border border-destructive/50 px-3 py-1 text-sm text-destructive hover:bg-destructive/10 focus:outline-2 focus:outline-ring">
										{m.btn_delete()}
									</button>
									{#if selectedTerm.status === 'CANDIDATE'}
										<button onclick={() => approveTerm(selectedTerm!.id)}
											class="rounded-md bg-success px-3 py-1 text-sm text-white hover:opacity-90 focus:outline-2 focus:outline-ring">
											{m.btn_approve()}
										</button>
										<button onclick={() => rejectTerm(selectedTerm!.id)}
											class="rounded-md bg-destructive px-3 py-1 text-sm text-white hover:opacity-90 focus:outline-2 focus:outline-ring">
											{m.btn_reject()}
										</button>
									{/if}
								</div>
							</div>
							{#if selectedTerm.description}
								<p class="mt-3 text-sm leading-relaxed">{selectedTerm.description}</p>
							{:else}
								<p class="mt-3 text-sm italic text-muted-foreground">{m.taxmgmt_no_desc()}</p>
							{/if}
						{/if}
						<dl class="mt-4 grid grid-cols-2 gap-2 text-sm">
							<div>
								<dt class="text-muted-foreground">{m.taxmgmt_normalized()}</dt>
								<dd class="font-mono text-xs">{selectedTerm.normalizedLabel}</dd>
							</div>
							{#if selectedTerm.sourceRef}
								<div>
									<dt class="text-muted-foreground">{m.taxmgmt_source_ref()}</dt>
									<dd class="font-mono text-xs">{selectedTerm.sourceRef}</dd>
								</div>
							{/if}
							<div>
								<dt class="text-muted-foreground">{m.label_created()}</dt>
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
								{m.taxmgmt_add_child()}
							</button>
							<button
								onclick={suggestChildren}
								disabled={suggesting}
								class="text-sm font-medium text-accent-foreground bg-accent/20 rounded-md px-3 py-1 hover:bg-accent/30 focus:outline-2 focus:outline-ring disabled:opacity-50"
							>
								{suggesting ? m.taxmgmt_thinking() : m.taxmgmt_ai_suggest()}
							</button>
						</div>

						{#if showSuggestions}
							<div class="mt-4">
								{#if suggesting}
									<p class="text-sm text-muted-foreground" aria-live="polite">{m.taxmgmt_suggesting({ label: selectedTerm.label })}</p>
								{:else if suggestedChildren.length === 0}
									<p class="text-sm text-muted-foreground">{m.taxmgmt_no_suggestions()}</p>
								{:else}
									<h3 class="text-sm font-semibold text-muted-foreground">{m.taxmgmt_suggested_heading()}</h3>
									<p class="text-xs text-muted-foreground">{m.taxmgmt_suggested_hint({ label: selectedTerm.label })}</p>
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
													{m.taxmgmt_add()}
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
		<div class="w-full max-w-md rounded-lg border border-border bg-card p-6 shadow-lg" role="dialog" aria-modal="true" aria-label={m.taxmgmt_add_dialog_label()}>
			<h2 class="text-lg font-semibold">{m.taxmgmt_add_dialog_title()}</h2>
			{#if selectedTerm}
				<p class="text-sm text-muted-foreground">{m.taxmgmt_parent_label({ label: selectedTerm.label })}</p>
			{/if}
			<form onsubmit={createTerm} class="mt-4 space-y-4">
				<div>
					<label for="term-label" class="block text-sm font-medium">{m.taxmgmt_label()} <span class="text-destructive">*</span></label>
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
						<label for="term-desc" class="block text-sm font-medium">{m.taxmgmt_desc_definition()} <span class="text-destructive">*</span></label>
						<button type="button" onclick={() => aiDefine(newLabel, 'create')} disabled={aiDefining || !newLabel.trim()}
							class="text-xs font-medium text-primary hover:underline focus:outline-2 focus:outline-ring disabled:opacity-50">
							{aiDefining ? m.taxmgmt_generating() : m.taxmgmt_ai_assist()}
						</button>
					</div>
					<textarea id="term-desc" bind:value={newDesc} rows="3" required
						placeholder={m.taxmgmt_desc_placeholder()}
						class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:outline-2 focus:outline-ring"></textarea>
				</div>
				<div class="flex justify-end gap-3 pt-2">
					<button type="button" onclick={() => { showCreate = false; duplicateWarning = ''; }}
						class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-ring">{m.btn_cancel()}</button>
					<button type="submit" disabled={!newLabel.trim() || !newDesc.trim() || creating || !!duplicateWarning}
						class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-ring disabled:opacity-50">
						{creating ? m.taxmgmt_creating() : m.taxmgmt_add_term_btn()}
					</button>
				</div>
			</form>
		</div>
	</div>
{/if}

<!-- Import dialog -->
{#if showImport}
	<!-- svelte-ignore a11y_no_static_element_interactions -->
	<div class="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
		onclick={(e) => { if (e.target === e.currentTarget) resetImportDialog(); }}
		onkeydown={(e) => { if (e.key === 'Escape') resetImportDialog(); }}>
		<div class="w-full max-w-2xl max-h-[85vh] overflow-y-auto rounded-lg border border-border bg-card p-6 shadow-lg" role="dialog" aria-modal="true" aria-label={m.taxmgmt_import_dialog_label()}>
			<h2 class="text-lg font-semibold">{m.taxmgmt_import_title()}</h2>
			<p class="mt-2 text-sm text-muted-foreground">{m.taxmgmt_import_desc()}</p>

			{#if importResult}
				<div class="mt-4 rounded-md border border-border p-4">
					<h3 class="font-semibold text-sm">{m.tax_import_complete()}</h3>
					<div class="mt-2 flex gap-4 text-sm">
						<span class="text-success font-medium">{m.tax_import_created({ count: importResult.created.toString() })}</span>
						<span class="text-muted-foreground">{m.tax_import_skipped({ count: importResult.skipped.toString() })}</span>
						{#if importResult.errors > 0}
							<span class="text-destructive font-medium">{m.tax_import_errors({ count: importResult.errors.toString() })}</span>
						{/if}
					</div>
					{#if importResult.details.length > 0}
						<div class="mt-3 max-h-48 overflow-y-auto">
							<table class="w-full text-xs">
								<thead>
									<tr class="border-b border-border text-left">
										<th class="pb-1 pr-2 font-medium">{m.tax_import_col_row()}</th>
										<th class="pb-1 pr-2 font-medium">{m.tax_import_col_label()}</th>
										<th class="pb-1 pr-2 font-medium">{m.tax_import_col_status()}</th>
										<th class="pb-1 font-medium">{m.tax_import_col_note()}</th>
									</tr>
								</thead>
								<tbody>
									{#each importResult.details as row}
										<tr class="border-b border-border/50">
											<td class="py-1 pr-2">{row.row}</td>
											<td class="py-1 pr-2">{row.label}</td>
											<td class="py-1 pr-2">
												<span class="rounded px-1.5 py-0.5 text-xs font-medium {row.status === 'created' ? 'bg-green-100 text-success' : row.status === 'error' ? 'bg-red-100 text-destructive' : 'bg-muted text-muted-foreground'}">
													{row.status}
												</span>
											</td>
											<td class="py-1 text-muted-foreground">{row.message ?? ''}</td>
										</tr>
									{/each}
								</tbody>
							</table>
						</div>
					{/if}
				</div>
				<div class="mt-4 flex justify-end">
					<button onclick={resetImportDialog}
						class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-ring">{m.tax_import_done()}</button>
				</div>
			{:else}
				<!-- File selection -->
				<div class="mt-4">
					<label class="block cursor-pointer rounded-lg border-2 border-dashed border-border p-6 text-center hover:border-primary">
						{#if importFileName}
							<p class="text-sm font-medium">{importFileName}</p>
							<p class="text-xs text-muted-foreground">{m.tax_import_format_detected({ format: importFormat.toUpperCase() })}</p>
						{:else}
							<p class="text-sm font-medium">{m.taxmgmt_choose_file()}</p>
							<p class="text-xs text-muted-foreground">{m.taxmgmt_file_formats()}</p>
						{/if}
						<input type="file" class="sr-only" accept=".json,.csv,.rdf,.xml" onchange={onImportFilePick} />
					</label>
				</div>

				<!-- Source reference (optional) -->
				<div class="mt-3">
					<label for="import-source" class="block text-xs font-medium text-muted-foreground">{m.tax_import_source_label()}</label>
					<input id="import-source" type="text" bind:value={importSourceRef}
						placeholder={m.tax_import_source_placeholder()}
						class="mt-1 w-full rounded-md border border-border bg-background px-3 py-1.5 text-sm focus:outline-2 focus:outline-ring" />
				</div>

				<!-- Format examples -->
				<details class="mt-3">
					<summary class="cursor-pointer text-xs font-medium text-primary hover:underline">{m.tax_import_formats_toggle()}</summary>
					<div class="mt-2 space-y-3 text-xs text-muted-foreground">
						<div>
							<p class="font-semibold text-foreground">CSV</p>
							<pre class="mt-1 rounded bg-muted p-2 overflow-x-auto">label,description,parent_label
Finance,Financial operations,
Accounts Payable,Vendor payment management,Finance
Accounts Receivable,Customer billing,Finance</pre>
						</div>
						<div>
							<p class="font-semibold text-foreground">JSON (nested)</p>
							<pre class="mt-1 rounded bg-muted p-2 overflow-x-auto">{`[
  {"label":"Finance","description":"Financial operations","children":[
    {"label":"Accounts Payable","description":"Vendor payments"},
    {"label":"Accounts Receivable","description":"Customer billing"}
  ]}
]`}</pre>
						</div>
						<div>
							<p class="font-semibold text-foreground">JSON (flat)</p>
							<pre class="mt-1 rounded bg-muted p-2 overflow-x-auto">{`[
  {"label":"Finance","description":"Financial operations"},
  {"label":"Accounts Payable","parent":"Finance"}
]`}</pre>
						</div>
						<div>
							<p class="font-semibold text-foreground">XML</p>
							<pre class="mt-1 rounded bg-muted p-2 overflow-x-auto">{`<taxonomy>
  <term label="Finance" description="Financial operations">
    <term label="Accounts Payable" />
  </term>
</taxonomy>`}</pre>
						</div>
					</div>
				</details>

				{#if importError}
					<div role="alert" class="mt-3 rounded-md border border-destructive bg-destructive/10 p-3 text-sm text-destructive">{importError}</div>
				{/if}

				<!-- Preview results -->
				{#if importPreview}
					<div class="mt-4 rounded-md border border-border p-4">
						<h3 class="font-semibold text-sm">{m.tax_import_preview_title()}</h3>
						<div class="mt-2 flex gap-4 text-sm">
							<span>{m.tax_import_terms_found({ count: importPreview.totalRows.toString() })}</span>
							<span class="text-success font-medium">{m.tax_import_new({ count: importPreview.newTerms.toString() })}</span>
							{#if importPreview.duplicates > 0}
								<span class="text-muted-foreground">{m.tax_import_dupes_skip({ count: importPreview.duplicates.toString() })}</span>
							{/if}
							{#if importPreview.errors > 0}
								<span class="text-destructive font-medium">{m.tax_import_errors({ count: importPreview.errors.toString() })}</span>
							{/if}
						</div>
						{#if importPreview.rows.length > 0}
							<div class="mt-3 max-h-48 overflow-y-auto">
								<table class="w-full text-xs">
									<thead>
										<tr class="border-b border-border text-left">
											<th class="pb-1 pr-2 font-medium">{m.tax_import_col_row()}</th>
											<th class="pb-1 pr-2 font-medium">{m.tax_import_col_label()}</th>
											<th class="pb-1 pr-2 font-medium">{m.tax_import_col_parent()}</th>
											<th class="pb-1 font-medium">{m.tax_import_col_status()}</th>
										</tr>
									</thead>
									<tbody>
										{#each importPreview.rows as row}
											<tr class="border-b border-border/50">
												<td class="py-1 pr-2">{row.row}</td>
												<td class="py-1 pr-2">{row.label}</td>
												<td class="py-1 pr-2 text-muted-foreground">{row.parentLabel || '-'}</td>
												<td class="py-1">
													{#if row.ok}
														<span class="text-success font-medium">OK</span>
													{:else}
														<span class="text-destructive" title={row.errors.join('; ')}>{row.errors[0]}</span>
													{/if}
												</td>
											</tr>
										{/each}
									</tbody>
								</table>
							</div>
						{/if}
					</div>
				{/if}

				<!-- Actions -->
				<div class="mt-4 flex justify-end gap-3">
					<button onclick={resetImportDialog}
						class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-ring">{m.btn_cancel()}</button>
					{#if !importPreview}
						<button onclick={previewImport} disabled={!importFileContent.trim() || importPreviewing}
							class="rounded-md border border-primary px-4 py-2 text-sm font-medium text-primary hover:bg-primary/10 focus:outline-2 focus:outline-ring disabled:opacity-50">
							{importPreviewing ? m.tax_import_previewing() : m.tax_import_preview_btn()}
						</button>
					{:else}
						<button onclick={previewImport} disabled={importPreviewing}
							class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-ring disabled:opacity-50">
							{m.tax_import_repreview()}
						</button>
						<button onclick={commitImport} disabled={importCommitting || importPreview.newTerms === 0}
							class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-ring disabled:opacity-50">
							{importCommitting ? m.tax_import_committing() : m.tax_import_commit_btn({ count: importPreview.newTerms.toString() })}
						</button>
					{/if}
				</div>
			{/if}
		</div>
	</div>
{/if}
