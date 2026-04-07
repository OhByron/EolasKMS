<script lang="ts">
	import { onMount } from 'svelte';
	import { page } from '$app/state';
	import { api } from '$lib/api';
	import type { DocumentDetail, VersionDetail } from '$lib/types/api';
	import PageHeader from '$lib/components/kosha/PageHeader.svelte';
	import StatusBadge from '$lib/components/kosha/StatusBadge.svelte';
	import ErrorBoundary from '$lib/components/kosha/ErrorBoundary.svelte';
	import * as m from '$paraglide/messages';

	let doc = $state<DocumentDetail | null>(null);
	let versions = $state<VersionDetail[]>([]);
	let loading = $state(true);
	let error = $state('');

	const docId = $derived(page.params.id);

	onMount(() => loadData());

	async function loadData() {
		loading = true;
		error = '';
		try {
			const [docRes, verRes] = await Promise.all([
				api.documents.get(docId),
				api.documents.versions(docId)
			]);
			doc = docRes.data;
			versions = verRes.data;
		} catch (e: any) {
			error = e.message;
		} finally {
			loading = false;
		}
	}

	function formatDate(iso: string): string {
		return new Date(iso).toLocaleDateString('en-US', {
			year: 'numeric',
			month: 'short',
			day: 'numeric',
			hour: '2-digit',
			minute: '2-digit'
		});
	}

	function formatSize(bytes: number | null): string {
		if (!bytes) return '-';
		if (bytes < 1024) return `${bytes} B`;
		if (bytes < 1048576) return `${(bytes / 1024).toFixed(1)} KB`;
		return `${(bytes / 1048576).toFixed(1)} MB`;
	}
</script>

<svelte:head>
	<title>{m.page_title_version_history()}{doc ? ` - ${doc.title}` : ''} - {m.nav_app_title()}</title>
</svelte:head>

{#if loading}
	<p aria-live="polite" class="text-muted-foreground">{m.versions_loading()}</p>
{:else if error}
	<ErrorBoundary {error} onRetry={loadData} />
{:else if doc}
	<PageHeader
		title={m.versions_title()}
		description={doc.title}
	>
		<a
			href="/documents/{docId}"
			class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring"
		>
			{m.btn_back_to_doc()}
		</a>
	</PageHeader>

	{#if versions.length === 0}
		<p class="mt-6 text-muted-foreground">{m.doc_versions_empty()}</p>
	{:else}
		<div class="mt-6 space-y-4">
			{#each versions as ver, i}
				<article
					class="rounded-lg border border-border bg-card p-5"
					class:border-primary={i === 0}
				>
					<div class="flex items-start justify-between">
						<div>
							<div class="flex items-center gap-3">
								<h2 class="text-lg font-semibold">v{ver.versionNumber}</h2>
								<StatusBadge status={ver.status} />
								{#if i === 0}
									<span class="rounded-md bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary">
										{m.versions_current()}
									</span>
								{/if}
							</div>
							<p class="mt-1 text-sm text-muted-foreground">
								{ver.fileName} &middot; {formatSize(ver.fileSizeBytes)}
							</p>
						</div>
						<time class="text-sm text-muted-foreground" datetime={ver.createdAt}>
							{formatDate(ver.createdAt)}
						</time>
					</div>

					{#if ver.changeSummary}
						<p class="mt-3 text-sm">{ver.changeSummary}</p>
					{/if}

					{#if ver.metadata}
						<details class="mt-3">
							<summary class="cursor-pointer text-sm font-medium text-primary hover:underline focus:outline-2 focus:outline-ring">
								{m.versions_ai_metadata()}
							</summary>
							<div class="mt-2 rounded-md bg-muted/50 p-3 text-sm">
								{#if ver.metadata.summary}
									<p><strong>{m.versions_ai_summary()}</strong> {ver.metadata.summary}</p>
								{/if}
								{#if ver.metadata.aiConfidence != null}
									<p class="mt-1">
										<strong>{m.versions_ai_confidence()}</strong> {Math.round(ver.metadata.aiConfidence * 100)}%
										{#if ver.metadata.humanReviewed}
											<span class="ml-2 text-success">{m.versions_human_reviewed()}</span>
										{/if}
									</p>
								{/if}
							</div>
						</details>
					{/if}

					{#if ver.contentHash}
						<p class="mt-2 font-mono text-xs text-muted-foreground">
							SHA-256: {ver.contentHash}
						</p>
					{/if}
				</article>
			{/each}
		</div>
	{/if}
{/if}
