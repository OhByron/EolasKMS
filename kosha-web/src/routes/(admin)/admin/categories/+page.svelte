<script lang="ts">
	import { onMount } from 'svelte';
	import { api } from '$lib/api';
	import type { DocumentCategory } from '$lib/types/api';
	import PageHeader from '$lib/components/kosha/PageHeader.svelte';
	import ErrorBoundary from '$lib/components/kosha/ErrorBoundary.svelte';

	/**
	 * Document Categories admin page.
	 *
	 * The primary purpose of this page is to let global admins flag which
	 * categories should pre-tick the "requires legal review" checkbox on the
	 * upload form. Name and description editing are supported too because the
	 * backend PATCH endpoint already allows them and categories are otherwise
	 * unsurfaced in the UI — but we keep the form tiny to avoid scope creep.
	 *
	 * Creation and deletion of categories are deliberately NOT exposed here.
	 * The seed set of six categories (Form, Manual, Policy, Procedure, Report,
	 * Specification) is considered stable for v1. Add/delete will come later
	 * with a dedicated taxonomy management effort.
	 */

	let categories = $state<DocumentCategory[]>([]);
	let loading = $state(true);
	let error = $state('');

	// Per-row save state — we track which rows are currently saving and
	// which rows have a result banner to show. Keyed by category id.
	let savingIds = $state<Set<string>>(new Set());
	let rowMessages = $state<Record<string, string>>({});

	// Local edit buffers for name/description — keeping these out of the
	// displayed category objects means we can track dirty state without
	// mutating the loaded server data.
	let nameDrafts = $state<Record<string, string>>({});
	let descriptionDrafts = $state<Record<string, string>>({});

	onMount(() => load());

	async function load() {
		loading = true;
		error = '';
		try {
			const res = await api.documentCategories.list();
			categories = res.data;
			// Seed the draft buffers so inputs show the current values
			nameDrafts = Object.fromEntries(categories.map((c) => [c.id, c.name]));
			descriptionDrafts = Object.fromEntries(
				categories.map((c) => [c.id, c.description ?? ''])
			);
		} catch (e: any) {
			error = e.message;
		} finally {
			loading = false;
		}
	}

	function nameDirty(cat: DocumentCategory): boolean {
		return (nameDrafts[cat.id] ?? '').trim() !== cat.name;
	}

	function descriptionDirty(cat: DocumentCategory): boolean {
		return (descriptionDrafts[cat.id] ?? '') !== (cat.description ?? '');
	}

	function anyTextDirty(cat: DocumentCategory): boolean {
		return nameDirty(cat) || descriptionDirty(cat);
	}

	async function saveTextEdits(cat: DocumentCategory) {
		const name = (nameDrafts[cat.id] ?? '').trim();
		const description = descriptionDrafts[cat.id] ?? '';
		if (!name) {
			rowMessages = { ...rowMessages, [cat.id]: 'Name is required' };
			return;
		}
		await patchCategory(cat.id, { name, description: description || null });
	}

	async function toggleSuggestsLegalReview(cat: DocumentCategory, next: boolean) {
		await patchCategory(cat.id, { suggestsLegalReview: next });
	}

	async function patchCategory(
		id: string,
		body: Partial<{ name: string; description: string | null; status: string; suggestsLegalReview: boolean }>
	) {
		savingIds = new Set([...savingIds, id]);
		rowMessages = { ...rowMessages, [id]: '' };
		try {
			const res = await api.documentCategories.update(id, body);
			// Splice the updated row back into the list without re-fetching
			const updated = res.data;
			categories = categories.map((c) => (c.id === id ? updated : c));
			nameDrafts = { ...nameDrafts, [id]: updated.name };
			descriptionDrafts = { ...descriptionDrafts, [id]: updated.description ?? '' };
			rowMessages = { ...rowMessages, [id]: 'Saved' };
			// Clear the success message after a moment
			setTimeout(() => {
				if (rowMessages[id] === 'Saved') {
					const next = { ...rowMessages };
					delete next[id];
					rowMessages = next;
				}
			}, 2000);
		} catch (e: any) {
			rowMessages = { ...rowMessages, [id]: e.message ?? 'Save failed' };
		} finally {
			const next = new Set(savingIds);
			next.delete(id);
			savingIds = next;
		}
	}
</script>

<svelte:head>
	<title>Document Categories - Administration - Eòlas</title>
</svelte:head>

<PageHeader
	title="Document Categories"
	description="Edit category metadata and flag which categories should suggest legal review on upload."
/>

{#if loading}
	<p aria-live="polite" class="mt-6 text-muted-foreground">Loading categories...</p>
{:else if error}
	<div class="mt-6"><ErrorBoundary {error} onRetry={load} /></div>
{:else}
	<div class="mt-6 space-y-3">
		<p class="text-xs text-muted-foreground">
			When a category is flagged as <em>suggests legal review</em>, the document upload form pre-ticks
			the "requires legal review" checkbox whenever a submitter picks that category. Submitters can
			always override the suggestion.
		</p>

		{#each categories as cat (cat.id)}
			{@const busy = savingIds.has(cat.id)}
			{@const message = rowMessages[cat.id] ?? ''}
			<section class="rounded-lg border border-border bg-card p-5" aria-label="Category {cat.name}">
				<div class="flex items-start justify-between gap-4">
					<div class="flex-1 space-y-3">
						<div>
							<label for="name-{cat.id}" class="block text-xs font-medium text-muted-foreground">
								Name
							</label>
							<input
								id="name-{cat.id}"
								type="text"
								bind:value={nameDrafts[cat.id]}
								disabled={busy}
								maxlength="200"
								class="mt-1 w-full rounded-md border border-border bg-background px-3 py-1.5 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
							/>
						</div>
						<div>
							<label for="desc-{cat.id}" class="block text-xs font-medium text-muted-foreground">
								Description
							</label>
							<textarea
								id="desc-{cat.id}"
								bind:value={descriptionDrafts[cat.id]}
								disabled={busy}
								rows="2"
								class="mt-1 w-full rounded-md border border-border bg-background px-3 py-1.5 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
							></textarea>
						</div>

						<label class="flex items-start gap-2 text-sm">
							<input
								type="checkbox"
								checked={cat.suggestsLegalReview}
								disabled={busy}
								onchange={(e) => toggleSuggestsLegalReview(cat, (e.target as HTMLInputElement).checked)}
								class="mt-0.5 focus:ring-ring"
							/>
							<span>
								Suggests legal review
								<span class="block text-xs text-muted-foreground">
									Pre-ticks the "requires legal review" checkbox on the upload form
									when this category is selected.
								</span>
							</span>
						</label>
					</div>

					<div class="flex shrink-0 flex-col items-end gap-2">
						<span class="text-xs text-muted-foreground">
							Status: <span class="font-medium">{cat.status}</span>
						</span>
						<button
							type="button"
							onclick={() => saveTextEdits(cat)}
							disabled={busy || !anyTextDirty(cat)}
							class="rounded-md border border-border bg-background px-3 py-1.5 text-xs font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50 disabled:cursor-not-allowed"
						>
							{busy ? 'Saving...' : 'Save text edits'}
						</button>
						{#if message}
							<span
								class="text-xs"
								class:text-success={message === 'Saved'}
								class:text-destructive={message !== 'Saved'}
								aria-live="polite"
							>
								{message}
							</span>
						{/if}
					</div>
				</div>
			</section>
		{/each}
	</div>
{/if}
