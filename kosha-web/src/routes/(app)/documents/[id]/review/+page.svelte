<script lang="ts">
	import { onMount } from 'svelte';
	import * as m from '$paraglide/messages';
	import { page } from '$app/state';
	import { goto } from '$app/navigation';
	import { api } from '$lib/api';
	import type { DocumentDetail, VersionDetail } from '$lib/types/api';
	import PageHeader from '$lib/components/kosha/PageHeader.svelte';
	import StatusBadge from '$lib/components/kosha/StatusBadge.svelte';
	import ErrorBoundary from '$lib/components/kosha/ErrorBoundary.svelte';
	import DocumentPreview from '$lib/components/kosha/DocumentPreview.svelte';

	let doc = $state<DocumentDetail | null>(null);
	let versions = $state<VersionDetail[]>([]);
	let loading = $state(true);
	let error = $state('');
	let actionLoading = $state(false);
	let comments = $state('');
	let showHistory = $state(false);

	const docId = $derived(page.params.id ?? '');
	const latestVersion = $derived(versions[0] ?? null);

	// The inbox deep-links into this page with ?workflow=<id>&step=<id> so
	// the review actions know which step instance to act on. Without those
	// params the page falls back to read-only view since we can't tell the
	// engine which step the reviewer is deciding on.
	const workflowInstanceId = $derived(page.url.searchParams.get('workflow') ?? '');
	const stepInstanceId = $derived(page.url.searchParams.get('step') ?? '');
	const canAct = $derived(workflowInstanceId.length > 0 && stepInstanceId.length > 0);

	onMount(() => loadDocument());

	async function loadDocument() {
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

	async function approve() {
		if (!doc || !canAct) return;
		actionLoading = true;
		error = '';
		try {
			await api.workflows.approve(workflowInstanceId, stepInstanceId, comments || undefined);
			goto('/inbox');
		} catch (e: any) {
			error = e.message;
		} finally {
			actionLoading = false;
		}
	}

	async function reject() {
		if (!doc || !canAct) return;
		if (!comments.trim()) {
			error = m.review_rejection_required();
			return;
		}
		actionLoading = true;
		error = '';
		try {
			await api.workflows.reject(workflowInstanceId, stepInstanceId, comments);
			goto('/inbox');
		} catch (e: any) {
			error = e.message;
		} finally {
			actionLoading = false;
		}
	}

	function formatDate(iso: string): string {
		return new Date(iso).toLocaleDateString('en-US', {
			year: 'numeric', month: 'short', day: 'numeric'
		});
	}

	function formatSize(bytes: number | null): string {
		if (!bytes) return '-';
		if (bytes < 1048576) return `${(bytes / 1024).toFixed(1)} KB`;
		return `${(bytes / 1048576).toFixed(1)} MB`;
	}
</script>

<svelte:head>
	<title>Review{doc ? ` - ${doc.title}` : ''} - Eòlas</title>
</svelte:head>

{#if loading}
	<p aria-live="polite" class="text-muted-foreground">Loading document for review...</p>
{:else if error && !doc}
	<ErrorBoundary {error} onRetry={loadDocument} />
{:else if doc}
	<PageHeader title={m.review_title()}>
		<a
			href="/inbox"
			class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring"
		>
			{m.review_back_inbox()}
		</a>
	</PageHeader>

	{#if error}
		<div role="alert" class="mt-4 rounded-md border border-destructive bg-destructive/10 p-3 text-sm text-destructive">
			{error}
		</div>
	{/if}

	<div class="mt-6 grid gap-6 lg:grid-cols-3">
		<!-- Left: Document preview (2 cols) -->
		<div class="space-y-4 lg:col-span-2">
			<!-- Document preview area -->
			<section class="rounded-lg border border-border bg-card p-6">
				<h2 class="text-xl font-semibold">{doc.title}</h2>
				{#if doc.description}
					<p class="mt-2 text-sm leading-relaxed text-muted-foreground">{doc.description}</p>
				{/if}

				{#if latestVersion}
					<div class="mt-4 rounded-md bg-muted/50 p-3 text-sm text-muted-foreground">
						Reviewing <span class="font-medium text-foreground">{latestVersion.fileName}</span>
						&middot; Version {latestVersion.versionNumber} &middot; {formatSize(latestVersion.fileSizeBytes)}
					</div>
				{/if}
			</section>

			<!-- Inline preview — reviewers decide by seeing the content,
				 not by guessing from metadata. Same component used on the
				 document detail page so behaviour is consistent across
				 the review surface. -->
			{#if latestVersion}
				<DocumentPreview
					documentId={doc.id}
					versionId={latestVersion.id}
					contentType={latestVersion.contentType}
					fileName={latestVersion.fileName}
					storageKey={latestVersion.storageKey}
				/>
			{/if}

			<!-- AI Summary -->
			{#if latestVersion?.metadata}
				<section class="rounded-lg border border-border bg-card p-5">
					<h2 class="text-sm font-semibold text-muted-foreground">AI Summary</h2>
					{#if latestVersion.metadata.summary}
						<p class="mt-2 text-sm leading-relaxed">{latestVersion.metadata.summary}</p>
					{:else}
						<p class="mt-2 text-sm text-muted-foreground">No AI summary available.</p>
					{/if}
					{#if latestVersion.metadata.aiConfidence != null}
						<p class="mt-2 text-xs text-muted-foreground">
							Confidence: {Math.round(latestVersion.metadata.aiConfidence * 100)}%
							{#if latestVersion.metadata.humanReviewed}
								<span class="ml-1 text-success">(Human reviewed)</span>
							{/if}
						</p>
					{/if}
				</section>
			{/if}
		</div>

		<!-- Right: Metadata + Actions (1 col) -->
		<div class="space-y-4">
			<!-- Metadata card -->
			<section class="rounded-lg border border-border bg-card p-5">
				<h2 class="mb-3 text-sm font-semibold text-muted-foreground">{m.label_details()}</h2>
				<dl class="space-y-2 text-sm">
					<div>
						<dt class="text-muted-foreground">{m.label_status()}</dt>
						<dd><StatusBadge status={doc.status} /></dd>
					</div>
					<div>
						<dt class="text-muted-foreground">{m.label_department()}</dt>
						<dd class="font-medium">{doc.departmentName}</dd>
					</div>
					{#if doc.categoryName}
						<div>
							<dt class="text-muted-foreground">{m.label_category()}</dt>
							<dd class="font-medium">{doc.categoryName}</dd>
						</div>
					{/if}
					<div>
						<dt class="text-muted-foreground">{m.label_created()}</dt>
						<dd class="font-medium">{formatDate(doc.createdAt)}</dd>
					</div>
				</dl>
			</section>

			<!-- Review action card -->
			<section class="rounded-lg border-2 border-primary/20 bg-card p-5">
				<h2 class="mb-3 text-sm font-semibold">{m.review_decision()}</h2>

				<div>
					<label for="review-comments" class="block text-sm font-medium text-muted-foreground">
						{m.label_comments()}
					</label>
					<textarea
						id="review-comments"
						bind:value={comments}
						rows="4"
						placeholder={m.review_comments_placeholder()}
						class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm placeholder:text-muted-foreground focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
					></textarea>
				</div>

				{#if !canAct}
					<p class="mt-3 rounded-md bg-muted/50 p-3 text-xs text-muted-foreground">
						{m.review_inbox_hint()}
					</p>
				{/if}

				<div class="mt-4 flex flex-col gap-2">
					<button
						onclick={approve}
						disabled={actionLoading || !canAct}
						class="w-full rounded-md bg-success px-4 py-2 text-sm font-medium text-white hover:opacity-90 focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
					>
						{m.btn_approve()}
					</button>
					<button
						onclick={reject}
						disabled={actionLoading || !canAct}
						class="w-full rounded-md border border-destructive bg-destructive/10 px-4 py-2 text-sm font-medium text-destructive hover:bg-destructive/20 focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
					>
						{m.btn_reject()}
					</button>
					<p class="text-xs text-muted-foreground">
						{m.review_reject_info()}
					</p>
				</div>
			</section>

			<!-- Version history (collapsible) -->
			<section class="rounded-lg border border-border bg-card p-5">
				<button
					onclick={() => (showHistory = !showHistory)}
					class="flex w-full items-center justify-between text-sm font-semibold text-muted-foreground focus:outline-2 focus:outline-ring"
					aria-expanded={showHistory}
				>
					<span>Version History ({versions.length})</span>
					<span aria-hidden="true">{showHistory ? '▲' : '▼'}</span>
				</button>

				{#if showHistory}
					<ul class="mt-3 space-y-2">
						{#each versions as ver}
							<li class="flex items-center justify-between text-sm">
								<span>
									<span class="font-medium">v{ver.versionNumber}</span>
									<span class="text-muted-foreground"> - {ver.fileName}</span>
								</span>
								<span class="text-xs text-muted-foreground">{formatDate(ver.createdAt)}</span>
							</li>
						{/each}
					</ul>
				{/if}
			</section>
		</div>
	</div>
{/if}
