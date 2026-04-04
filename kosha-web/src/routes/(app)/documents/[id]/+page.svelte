<script lang="ts">
	import { onMount } from 'svelte';
	import { page } from '$app/state';
	import { goto } from '$app/navigation';
	import { api } from '$lib/api';
	import { user, hasAnyRole } from '$lib/auth';
	import type { DocumentDetail, VersionDetail } from '$lib/types/api';
	import PageHeader from '$lib/components/kosha/PageHeader.svelte';
	import StatusBadge from '$lib/components/kosha/StatusBadge.svelte';
	import ErrorBoundary from '$lib/components/kosha/ErrorBoundary.svelte';

	interface Keyword {
		keyword: string;
		frequency: number;
		confidence: number;
	}

	let doc = $state<DocumentDetail | null>(null);
	let versions = $state<VersionDetail[]>([]);
	let keywords = $state<Keyword[]>([]);
	let loading = $state(true);
	let error = $state('');
	let actionLoading = $state(false);
	let successMsg = $state('');

	// Edit state
	let editing = $state(false);
	let editTitle = $state('');
	let editDesc = $state('');

	const docId = $derived(page.params.id ?? '');

	const statusTransitions: Record<string, { label: string; to: string; color: string }[]> = {
		DRAFT: [
			{ label: 'Submit for Review', to: 'IN_REVIEW', color: 'bg-accent text-accent-foreground' }
		],
		IN_REVIEW: [
			{ label: 'Approve & Publish', to: 'PUBLISHED', color: 'bg-success text-white' },
			{ label: 'Return to Draft', to: 'DRAFT', color: 'bg-muted text-muted-foreground' },
			{ label: 'Reject', to: 'REJECTED', color: 'bg-destructive text-destructive-foreground' }
		],
		PUBLISHED: [
			{ label: 'Archive', to: 'ARCHIVED', color: 'bg-muted text-muted-foreground' },
			{ label: 'Place on Legal Hold', to: 'LEGAL_HOLD', color: 'bg-destructive text-destructive-foreground' }
		],
		REJECTED: [
			{ label: 'Revert to Draft', to: 'DRAFT', color: 'bg-muted text-muted-foreground' }
		],
		ARCHIVED: [
			{ label: 'Republish', to: 'PUBLISHED', color: 'bg-success text-white' }
		],
		LEGAL_HOLD: [
			{ label: 'Release Hold & Archive', to: 'ARCHIVED', color: 'bg-muted text-muted-foreground' }
		]
	};

	const availableTransitions = $derived(
		doc ? (statusTransitions[doc.status] ?? []) : []
	);

	const isLockedByMe = $derived(doc?.checkedOut && doc?.lockedBy === $user?.profileId);
	const canEdit = $derived(
		doc && (!doc.checkedOut || isLockedByMe) && hasAnyRole('GLOBAL_ADMIN', 'DEPT_ADMIN', 'EDITOR')
	);

	onMount(() => loadDocument());

	async function loadDocument() {
		loading = true;
		error = '';
		try {
			const [docRes, verRes, kwRes] = await Promise.all([
				api.documents.get(docId),
				api.documents.versions(docId),
				api.get<Keyword[]>(`/api/v1/documents/${docId}/keywords`).catch(() => ({ data: [] as Keyword[] })),
			]);
			doc = docRes.data;
			versions = verRes.data;
			keywords = kwRes.data;
		} catch (e: any) {
			error = e.message;
		} finally {
			loading = false;
		}
	}

	function startEditing() {
		if (!doc) return;
		editTitle = doc.title;
		editDesc = doc.description ?? '';
		editing = true;
	}

	async function saveEdit() {
		if (!doc) return;
		actionLoading = true;
		error = '';
		try {
			const res = await api.documents.update(docId, {
				title: editTitle,
				description: editDesc || undefined
			});
			doc = res.data;
			editing = false;
			successMsg = 'Document updated.';
			setTimeout(() => (successMsg = ''), 3000);
		} catch (e: any) {
			error = e.message;
		} finally {
			actionLoading = false;
		}
	}

	async function changeStatus(newStatus: string) {
		if (!doc) return;
		actionLoading = true;
		error = '';
		try {
			const res = await api.documents.update(docId, { status: newStatus });
			doc = res.data;
			successMsg = `Status changed to ${newStatus.replace('_', ' ')}.`;
			setTimeout(() => (successMsg = ''), 3000);
		} catch (e: any) {
			error = e.message;
		} finally {
			actionLoading = false;
		}
	}

	async function checkout() {
		actionLoading = true;
		error = '';
		try {
			const res = await api.documents.checkout(docId);
			doc = res.data;
		} catch (e: any) {
			error = e.message;
		} finally {
			actionLoading = false;
		}
	}

	async function checkin() {
		actionLoading = true;
		error = '';
		try {
			const res = await api.documents.checkin(docId);
			doc = res.data;
		} catch (e: any) {
			error = e.message;
		} finally {
			actionLoading = false;
		}
	}

	async function deleteDocument() {
		if (!doc || !confirm('Are you sure you want to delete this document?')) return;
		actionLoading = true;
		try {
			await api.documents.delete(docId);
			goto('/documents');
		} catch (e: any) {
			error = e.message;
			actionLoading = false;
		}
	}

	function formatDate(iso: string): string {
		return new Date(iso).toLocaleDateString('en-US', {
			year: 'numeric', month: 'short', day: 'numeric'
		});
	}

	function formatSize(bytes: number | null): string {
		if (!bytes) return '—';
		if (bytes < 1024) return `${bytes} B`;
		if (bytes < 1048576) return `${(bytes / 1024).toFixed(1)} KB`;
		return `${(bytes / 1048576).toFixed(1)} MB`;
	}
</script>

<svelte:head>
	<title>{doc?.title ?? 'Document'} - Kosha</title>
</svelte:head>

{#if loading}
	<p aria-live="polite" class="text-muted-foreground">Loading document...</p>
{:else if error && !doc}
	<ErrorBoundary {error} onRetry={loadDocument} />
{:else if doc}
	<PageHeader title={editing ? 'Edit Document' : doc.title} description={doc.docNumber ? `#${doc.docNumber}` : undefined}>
		{#if !editing}
			{#if doc.checkedOut && doc.lockedBy === $user?.profileId}
				<button onclick={checkin} disabled={actionLoading}
					class="rounded-md bg-success px-4 py-2 text-sm font-medium text-white hover:opacity-90 focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50">
					Check In
				</button>
			{:else if !doc.checkedOut}
				<button onclick={checkout} disabled={actionLoading}
					class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50">
					Check Out
				</button>
			{/if}
			{#if canEdit}
				<button onclick={startEditing}
					class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring">
					Edit
				</button>
			{/if}
			<a href="/documents/{docId}/versions"
				class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring">
				Version History
			</a>
		{/if}
	</PageHeader>

	{#if error}
		<div role="alert" class="mt-4 rounded-md border border-destructive bg-destructive/10 p-3 text-sm text-destructive">{error}</div>
	{/if}
	{#if successMsg}
		<div role="status" class="mt-4 rounded-md border border-success bg-success/10 p-3 text-sm text-success">{successMsg}</div>
	{/if}

	<div class="mt-6 grid gap-6 lg:grid-cols-3">
		<!-- Left: Main info (2 cols) -->
		<div class="space-y-6 lg:col-span-2">
			<!-- Edit form OR description -->
			{#if editing}
				<section class="rounded-lg border-2 border-primary/20 bg-card p-5">
					<h2 class="text-sm font-semibold text-muted-foreground">Edit Document</h2>
					<form onsubmit={(e) => { e.preventDefault(); saveEdit(); }} class="mt-3 space-y-4">
						<div>
							<label for="edit-title" class="block text-sm font-medium">Title</label>
							<input id="edit-title" type="text" bind:value={editTitle} required maxlength="500"
								class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:outline-2 focus:outline-ring" />
						</div>
						<div>
							<label for="edit-desc" class="block text-sm font-medium">Description</label>
							<textarea id="edit-desc" bind:value={editDesc} rows="4" maxlength="5000"
								class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:outline-2 focus:outline-ring"></textarea>
						</div>
						<div class="flex gap-3">
							<button type="button" onclick={() => (editing = false)}
								class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-ring">
								Cancel
							</button>
							<button type="submit" disabled={actionLoading || !editTitle.trim()}
								class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-ring disabled:opacity-50">
								{actionLoading ? 'Saving...' : 'Save Changes'}
							</button>
						</div>
					</form>
				</section>
			{:else if doc.description}
				<section class="rounded-lg border border-border bg-card p-5">
					<h2 class="text-sm font-semibold text-muted-foreground">Description</h2>
					<p class="mt-2 text-sm leading-relaxed">{doc.description}</p>
				</section>
			{/if}

			<!-- AI Summary -->
			{#if doc.currentVersion}
				{@const ver = versions.find(v => v.id === doc?.currentVersion?.id)}
				{#if ver?.metadata}
					<section class="rounded-lg border border-border bg-card p-5">
						<div class="flex items-center justify-between">
							<h2 class="text-sm font-semibold text-muted-foreground">AI Summary</h2>
							{#if ver.metadata.aiConfidence != null}
								<span class="text-xs text-muted-foreground"
									aria-label="AI confidence: {Math.round(ver.metadata.aiConfidence * 100)}%">
									Confidence: {Math.round(ver.metadata.aiConfidence * 100)}%
								</span>
							{/if}
						</div>
						{#if ver.metadata.summary}
							<p class="mt-2 text-sm leading-relaxed">{ver.metadata.summary}</p>
						{:else}
							<p class="mt-2 text-sm text-muted-foreground">AI processing pending. Summary will appear when the AI sidecar processes this document.</p>
						{/if}
						{#if ver.metadata.humanReviewed}
							<p class="mt-2 text-xs text-success">Human reviewed</p>
						{/if}
					</section>
				{:else}
					<section class="rounded-lg border border-dashed border-border bg-card p-5">
						<h2 class="text-sm font-semibold text-muted-foreground">AI Summary</h2>
						<p class="mt-2 text-sm text-muted-foreground">No AI metadata yet. The AI sidecar will process this document when connected.</p>
					</section>
				{/if}
			{/if}

			<!-- Keywords -->
			{#if keywords.length > 0}
				<section class="rounded-lg border border-border bg-card p-5">
					<h2 class="text-sm font-semibold text-muted-foreground">Extracted Keywords</h2>
					<div class="mt-3 flex flex-wrap gap-2">
						{#each keywords as kw}
							<span
								class="inline-flex items-center gap-1 rounded-full border border-border px-3 py-1 text-sm"
								title="Frequency: {kw.frequency}, Confidence: {Math.round(kw.confidence * 100)}%"
							>
								{kw.keyword}
								<span class="text-xs text-muted-foreground">({kw.frequency})</span>
							</span>
						{/each}
					</div>
				</section>
			{/if}

			<!-- Recent Versions -->
			<section class="rounded-lg border border-border bg-card p-5">
				<div class="flex items-center justify-between">
					<h2 class="text-sm font-semibold text-muted-foreground">Recent Versions</h2>
					<a href="/documents/{docId}/versions"
						class="text-xs text-primary hover:underline focus:outline-2 focus:outline-ring">
						View all
					</a>
				</div>
				{#if versions.length === 0}
					<p class="mt-2 text-sm text-muted-foreground">No versions uploaded yet.</p>
				{:else}
					<ul class="mt-3 space-y-2">
						{#each versions.slice(0, 5) as ver}
							<li class="flex items-center justify-between rounded-md border border-border px-3 py-2 text-sm">
								<div>
									<span class="font-medium">v{ver.versionNumber}</span>
									<span class="ml-2 text-muted-foreground">{ver.fileName}</span>
								</div>
								<div class="flex items-center gap-2">
									<span class="text-xs text-muted-foreground">{formatSize(ver.fileSizeBytes)}</span>
									<StatusBadge status={ver.status} />
									<span class="text-xs text-muted-foreground">{formatDate(ver.createdAt)}</span>
								</div>
							</li>
						{/each}
					</ul>
				{/if}
			</section>
		</div>

		<!-- Right: Sidebar (1 col) -->
		<div class="space-y-4">
			<!-- Status + Actions card -->
			<section class="rounded-lg border-2 border-primary/10 bg-card p-5">
				<h2 class="text-sm font-semibold text-muted-foreground">Status</h2>
				<div class="mt-2 flex items-center gap-2">
					<StatusBadge status={doc.status} />
					{#if doc.checkedOut}
						<StatusBadge status="LOCKED" />
					{/if}
				</div>

				{#if availableTransitions.length > 0 && canEdit}
					<div class="mt-4 space-y-2">
						<h3 class="text-xs font-semibold uppercase tracking-wider text-muted-foreground">Actions</h3>
						{#each availableTransitions as transition}
							<button
								onclick={() => changeStatus(transition.to)}
								disabled={actionLoading}
								class="w-full rounded-md px-3 py-2 text-sm font-medium transition focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50 {transition.color}"
							>
								{transition.label}
							</button>
						{/each}
					</div>
				{/if}
			</section>

			<!-- Details card -->
			<section class="rounded-lg border border-border bg-card p-5">
				<h2 class="mb-3 text-sm font-semibold text-muted-foreground">Details</h2>
				<dl class="space-y-2 text-sm">
					<div>
						<dt class="text-muted-foreground">Department</dt>
						<dd class="font-medium">{doc.departmentName}</dd>
					</div>
					{#if doc.categoryName}
						<div>
							<dt class="text-muted-foreground">Category</dt>
							<dd class="font-medium">{doc.categoryName}</dd>
						</div>
					{/if}
					<div>
						<dt class="text-muted-foreground">Storage</dt>
						<dd class="font-medium">{doc.storageMode}</dd>
					</div>
					<div>
						<dt class="text-muted-foreground">Workflow</dt>
						<dd class="font-medium">{doc.workflowType}</dd>
					</div>
					{#if doc.currentVersion}
						<div>
							<dt class="text-muted-foreground">Current Version</dt>
							<dd class="font-medium">v{doc.currentVersion.versionNumber}</dd>
						</div>
					{/if}
					<div>
						<dt class="text-muted-foreground">Created</dt>
						<dd class="font-medium">{formatDate(doc.createdAt)}</dd>
					</div>
					<div>
						<dt class="text-muted-foreground">Updated</dt>
						<dd class="font-medium">{formatDate(doc.updatedAt)}</dd>
					</div>
				</dl>
			</section>

			<!-- Danger zone -->
			{#if hasAnyRole('GLOBAL_ADMIN', 'DEPT_ADMIN')}
				<section class="rounded-lg border border-destructive/30 bg-card p-5">
					<h2 class="text-sm font-semibold text-destructive">Danger Zone</h2>
					<button onclick={deleteDocument} disabled={actionLoading}
						class="mt-3 w-full rounded-md border border-destructive bg-destructive/10 px-3 py-2 text-sm font-medium text-destructive hover:bg-destructive/20 focus:outline-2 focus:outline-ring disabled:opacity-50">
						Delete Document
					</button>
				</section>
			{/if}
		</div>
	</div>
{/if}
