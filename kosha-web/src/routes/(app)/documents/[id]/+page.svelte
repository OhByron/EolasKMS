<script lang="ts">
	import { onMount } from 'svelte';
	import { page } from '$app/state';
	import { goto } from '$app/navigation';
	import { api } from '$lib/api';
	import { user, hasAnyRole } from '$lib/auth';
	import type { DocumentDetail, DocumentSignature, ShareLinkCreated, ShareLinkSummary, VersionDetail } from '$lib/types/api';
	import PageHeader from '$lib/components/kosha/PageHeader.svelte';
	import StatusBadge from '$lib/components/kosha/StatusBadge.svelte';
	import ErrorBoundary from '$lib/components/kosha/ErrorBoundary.svelte';
	import DocumentPreview from '$lib/components/kosha/DocumentPreview.svelte';
	import * as m from '$paraglide/messages';

	interface Keyword {
		keyword: string;
		frequency: number;
		confidence: number;
	}

	let doc = $state<DocumentDetail | null>(null);
	let versions = $state<VersionDetail[]>([]);
	let keywords = $state<Keyword[]>([]);
	let signatures = $state<DocumentSignature[]>([]);
	let loading = $state(true);
	let error = $state('');
	let actionLoading = $state(false);
	let successMsg = $state('');

	// Signature state
	let showSignModal = $state(false);
	let signTypedName = $state('');
	let signing = $state(false);
	let signError = $state('');

	// Share link state
	let shareLinks = $state<ShareLinkSummary[]>([]);
	let showShareModal = $state(false);
	let shareExpiryDays = $state(7);
	let sharePassword = $state('');
	let shareMaxAccess = $state('');
	let creatingShare = $state(false);
	let shareError = $state('');
	let createdShareToken = $state<string | null>(null);

	// Edit state
	let editing = $state(false);
	let editTitle = $state('');
	let editDesc = $state('');

	// New version upload state. Kept separate from the title/description edit
	// flow because it's a fundamentally different operation (file upload + AI
	// re-processing) and users should be able to do either without stepping
	// through the other.
	let addingVersion = $state(false);
	let newVersionFile = $state<File | null>(null);
	let newVersionSummary = $state('');
	let versionUploading = $state(false);
	let versionError = $state('');

	const docId = $derived(page.params.id ?? '');

	const statusTransitions: Record<string, { label: string; to: string; color: string }[]> = {
		DRAFT: [
			{ label: m.doc_action_submit_review(), to: 'IN_REVIEW', color: 'bg-accent text-accent-foreground' }
		],
		IN_REVIEW: [
			{ label: m.doc_action_approve_publish(), to: 'PUBLISHED', color: 'bg-success text-white' },
			{ label: m.doc_action_return_draft(), to: 'DRAFT', color: 'bg-muted text-muted-foreground' },
			{ label: m.btn_reject(), to: 'REJECTED', color: 'bg-destructive text-destructive-foreground' }
		],
		PUBLISHED: [
			{ label: m.doc_action_archive(), to: 'ARCHIVED', color: 'bg-muted text-muted-foreground' },
			{ label: m.doc_action_legal_hold(), to: 'LEGAL_HOLD', color: 'bg-destructive text-destructive-foreground' }
		],
		REJECTED: [
			{ label: m.doc_action_revert_draft(), to: 'DRAFT', color: 'bg-muted text-muted-foreground' }
		],
		ARCHIVED: [
			{ label: m.doc_action_republish(), to: 'PUBLISHED', color: 'bg-success text-white' }
		],
		LEGAL_HOLD: [
			{ label: m.doc_action_release_hold(), to: 'ARCHIVED', color: 'bg-muted text-muted-foreground' }
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
			const [docRes, verRes, kwRes, sigRes, slRes] = await Promise.all([
				api.documents.get(docId),
				api.documents.versions(docId),
				api.get<Keyword[]>(`/api/v1/documents/${docId}/keywords`).catch(() => ({ data: [] as Keyword[] })),
				api.documents.signatures(docId).catch(() => ({ data: [] as DocumentSignature[] })),
				api.documents.shareLinks(docId).catch(() => ({ data: [] as ShareLinkSummary[] })),
			]);
			doc = docRes.data;
			signatures = sigRes.data;
			shareLinks = slRes.data;
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
			successMsg = m.doc_updated_success();
			setTimeout(() => (successMsg = ''), 3000);
		} catch (e: any) {
			error = e.message;
		} finally {
			actionLoading = false;
		}
	}

	function openVersionUpload() {
		addingVersion = true;
		newVersionFile = null;
		newVersionSummary = '';
		versionError = '';
	}

	function cancelVersionUpload() {
		addingVersion = false;
		newVersionFile = null;
		newVersionSummary = '';
		versionError = '';
	}

	function onVersionFileSelected(ev: Event) {
		const input = ev.target as HTMLInputElement;
		newVersionFile = input.files?.[0] ?? null;
	}

	/**
	 * Upload a new version for the current document. Mirrors the 3-step
	 * pipeline used by the initial upload form:
	 *   1. POST /documents/{id}/versions       — create the version row
	 *   2. POST .../versions/{verId}/upload    — stream the file bytes
	 *   3. reloadDocument()                    — pick up new currentVersion
	 * AI processing is kicked off by the backend on (2) and runs async; the
	 * version detail page can be used to monitor it. We intentionally do not
	 * block the detail page on AI completion.
	 */
	async function uploadNewVersion() {
		if (!doc || !newVersionFile) return;
		versionUploading = true;
		versionError = '';
		try {
			const verRes = await api.documents.createVersion(doc.id, {
				fileName: newVersionFile.name,
				fileSizeBytes: newVersionFile.size,
				changeSummary: newVersionSummary.trim() || undefined,
			});
			const newVersion = verRes.data;

			const formData = new FormData();
			formData.append('file', newVersionFile);

			// Reuse the same bearer-token pattern the initial upload uses —
			// the /versions/{id}/upload endpoint is JWT-authenticated and
			// multipart, so we can't go through the generic api.request wrapper.
			let token = '';
			const unsub = user.subscribe((u) => { token = u?.accessToken ?? ''; });
			unsub();

			const uploadRes = await fetch(
				`/api/v1/documents/${doc.id}/versions/${newVersion.id}/upload`,
				{
					method: 'POST',
					headers: { Authorization: `Bearer ${token}` },
					body: formData,
				},
			);
			if (!uploadRes.ok) {
				const err = await uploadRes.json().catch(() => ({ detail: 'Upload failed' }));
				throw new Error(err.detail ?? `Upload failed: HTTP ${uploadRes.status}`);
			}

			await loadDocument();
			addingVersion = false;
			newVersionFile = null;
			newVersionSummary = '';
			successMsg = m.doc_version_uploaded({ version: String(newVersion.versionNumber) });
			setTimeout(() => (successMsg = ''), 4000);
		} catch (e: any) {
			versionError = e.message ?? 'Failed to upload new version';
		} finally {
			versionUploading = false;
		}
	}

	async function changeStatus(newStatus: string) {
		if (!doc) return;
		actionLoading = true;
		error = '';
		try {
			const res = await api.documents.update(docId, { status: newStatus });
			doc = res.data;
			successMsg = m.doc_status_changed({ status: newStatus.replace('_', ' ') });
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
		if (!doc || !confirm(m.doc_delete_confirm())) return;
		actionLoading = true;
		try {
			await api.documents.delete(docId);
			goto('/documents');
		} catch (e: any) {
			error = e.message;
			actionLoading = false;
		}
	}

	// ── Share link handlers ──────────────────────────────────────

	function openShareModal() {
		shareExpiryDays = 7;
		sharePassword = '';
		shareMaxAccess = '';
		shareError = '';
		createdShareToken = null;
		showShareModal = true;
	}

	function closeShareModal() {
		if (creatingShare) return;
		showShareModal = false;
		createdShareToken = null;
	}

	async function createShareLink() {
		if (!doc) return;
		const currentVer = versions[0];
		if (!currentVer) { shareError = 'No version available'; return; }
		creatingShare = true;
		shareError = '';
		try {
			const res = await api.documents.createShareLink(doc.id, {
				versionId: currentVer.id,
				expiryDays: shareExpiryDays,
				password: sharePassword || undefined,
				maxAccess: shareMaxAccess ? parseInt(shareMaxAccess, 10) : undefined,
			});
			createdShareToken = res.data.token;
			// Refresh the list
			const slRes = await api.documents.shareLinks(doc.id);
			shareLinks = slRes.data;
		} catch (e: any) {
			shareError = e.message ?? 'Failed to create share link';
		} finally {
			creatingShare = false;
		}
	}

	async function copyShareUrl() {
		if (!createdShareToken) return;
		const url = `${window.location.origin}/share/${createdShareToken}`;
		try {
			await navigator.clipboard.writeText(url);
			successMsg = m.doc_share_copied();
			setTimeout(() => (successMsg = ''), 2500);
		} catch {
			shareError = m.doc_share_copy_failed();
		}
	}

	async function revokeShareLink(linkId: string) {
		if (!doc) return;
		try {
			await api.documents.revokeShareLink(doc.id, linkId);
			const slRes = await api.documents.shareLinks(doc.id);
			shareLinks = slRes.data;
		} catch (e: any) {
			error = e.message ?? 'Failed to revoke';
		}
	}

	// ── Signature handlers ───────────────────────────────────────

	function openSignModal() {
		signTypedName = '';
		signError = '';
		showSignModal = true;
	}

	function closeSignModal() {
		if (signing) return;
		showSignModal = false;
	}

	async function submitSignature() {
		if (!doc || !signTypedName.trim()) {
			signError = m.doc_sign_error_name();
			return;
		}
		const currentVer = versions[0];
		if (!currentVer) {
			signError = m.doc_sign_error_version();
			return;
		}
		signing = true;
		signError = '';
		try {
			await api.documents.sign(doc.id, currentVer.id, {
				typedName: signTypedName.trim(),
				contentHash: currentVer.contentHash ?? undefined,
			});
			// Reload signatures
			const sigRes = await api.documents.signatures(doc.id);
			signatures = sigRes.data;
			showSignModal = false;
			successMsg = m.doc_sign_success();
			setTimeout(() => (successMsg = ''), 3000);
		} catch (e: any) {
			signError = e.message ?? 'Signing failed';
		} finally {
			signing = false;
		}
	}

	function formatDate(iso: string): string {
		return new Date(iso).toLocaleDateString('en-US', {
			year: 'numeric', month: 'short', day: 'numeric'
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
	<title>{doc?.title ?? 'Document'} - Eòlas</title>
</svelte:head>

{#if loading}
	<p aria-live="polite" class="text-muted-foreground">{m.doc_loading_document()}</p>
{:else if error && !doc}
	<ErrorBoundary {error} onRetry={loadDocument} />
{:else if doc}
	<PageHeader title={editing ? m.doc_edit_document() : doc.title} description={doc.docNumber ? `#${doc.docNumber}` : undefined}>
		{#if !editing}
			{#if doc.checkedOut && doc.lockedBy === $user?.profileId}
				<button onclick={checkin} disabled={actionLoading}
					class="rounded-md bg-success px-4 py-2 text-sm font-medium text-white hover:opacity-90 focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50">
					{m.btn_check_in()}
				</button>
			{:else if !doc.checkedOut}
				<button onclick={checkout} disabled={actionLoading}
					class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50">
					{m.btn_check_out()}
				</button>
			{/if}
			{#if canEdit}
				<button onclick={startEditing}
					class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring">
					{m.btn_edit()}
				</button>
			{/if}
			<a href="/documents/{docId}/versions"
				class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring">
				{m.btn_version_history()}
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
					<h2 class="text-sm font-semibold text-muted-foreground">{m.doc_edit_document()}</h2>
					<form onsubmit={(e) => { e.preventDefault(); saveEdit(); }} class="mt-3 space-y-4">
						<div>
							<label for="edit-title" class="block text-sm font-medium">{m.label_title()}</label>
							<input id="edit-title" type="text" bind:value={editTitle} required maxlength="500"
								class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:outline-2 focus:outline-ring" />
						</div>
						<div>
							<label for="edit-desc" class="block text-sm font-medium">{m.label_description()}</label>
							<textarea id="edit-desc" bind:value={editDesc} rows="4" maxlength="5000"
								class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:outline-2 focus:outline-ring"></textarea>
						</div>
						<div class="flex gap-3">
							<button type="button" onclick={() => (editing = false)}
								class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-ring">
								{m.btn_cancel()}
							</button>
							<button type="submit" disabled={actionLoading || !editTitle.trim()}
								class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-ring disabled:opacity-50">
								{actionLoading ? m.btn_saving() : m.btn_save_changes()}
							</button>
						</div>
					</form>
				</section>
			{:else if doc.description}
				<section class="rounded-lg border border-border bg-card p-5">
					<h2 class="text-sm font-semibold text-muted-foreground">{m.doc_description_heading()}</h2>
					<p class="mt-2 text-sm leading-relaxed">{doc.description}</p>
				</section>
			{/if}

			<!-- Document preview (Pass 4.1). Shows the current version's
				 bytes rendered in-browser: PDF via pdf.js, images native,
				 text/code as pre, everything else as a download button.
				 Falls back gracefully if the version pre-dates the storage
				 write path (pre-Pass-4.1 uploads have no storageKey). -->
			{#if !editing && doc.currentVersion}
				{@const currentVer = versions.find((v) => v.id === doc?.currentVersion?.id)}
				{#if currentVer}
					<section class="space-y-2">
						<h2 class="text-sm font-semibold text-muted-foreground">{m.doc_preview_heading()}</h2>
						<DocumentPreview
							documentId={doc.id}
							versionId={currentVer.id}
							contentType={currentVer.contentType}
							fileName={currentVer.fileName}
							storageKey={currentVer.storageKey}
						/>
					</section>
				{/if}
			{/if}

			<!-- AI Summary -->
			{#if doc.currentVersion}
				{@const ver = versions.find(v => v.id === doc?.currentVersion?.id)}
				{#if ver?.metadata}
					<section class="rounded-lg border border-border bg-card p-5">
						<div class="flex items-center justify-between">
							<h2 class="text-sm font-semibold text-muted-foreground">{m.doc_ai_summary()}</h2>
							{#if ver.metadata.aiConfidence != null}
								<span class="text-xs text-muted-foreground"
									aria-label="AI confidence: {Math.round(ver.metadata.aiConfidence * 100)}%">
									{m.doc_ai_confidence({ pct: String(Math.round(ver.metadata.aiConfidence * 100)) })}
								</span>
							{/if}
						</div>
						{#if ver.metadata.summary}
							<p class="mt-2 text-sm leading-relaxed">{ver.metadata.summary}</p>
						{:else}
							<p class="mt-2 text-sm text-muted-foreground">{m.doc_ai_pending()}</p>
						{/if}
						{#if ver.metadata.humanReviewed}
							<p class="mt-2 text-xs text-success">{m.doc_ai_human_reviewed()}</p>
						{/if}
					</section>
				{:else}
					<section class="rounded-lg border border-dashed border-border bg-card p-5">
						<h2 class="text-sm font-semibold text-muted-foreground">{m.doc_ai_summary()}</h2>
						<p class="mt-2 text-sm text-muted-foreground">{m.doc_ai_no_metadata()}</p>
					</section>
				{/if}
			{/if}

			<!-- Keywords -->
			{#if keywords.length > 0}
				<section class="rounded-lg border border-border bg-card p-5">
					<h2 class="text-sm font-semibold text-muted-foreground">{m.doc_keywords()}</h2>
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

			<!-- Extracted Metadata (Pass 5.3.0) -->
			{#if doc.currentVersion}
				{@const currentMeta = versions.find((v) => v.id === doc?.currentVersion?.id)?.extractedMetadata}
				{#if currentMeta && Object.keys(currentMeta).length > 0}
					<section class="rounded-lg border border-border bg-card p-5">
						<h2 class="text-sm font-semibold text-muted-foreground">{m.doc_extracted_metadata()}</h2>
						<dl class="mt-3 space-y-2 text-sm">
							{#each Object.entries(currentMeta) as [key, value]}
								<div class="flex justify-between gap-2">
									<dt class="text-muted-foreground">{key.replace(/_/g, ' ')}</dt>
									<dd class="font-medium text-right">
										{#if Array.isArray(value)}
											{value.join(', ')}
										{:else}
											{String(value)}
										{/if}
									</dd>
								</div>
							{/each}
						</dl>
					</section>
				{/if}
			{/if}

			<!-- Recent Versions -->
			<section class="rounded-lg border border-border bg-card p-5">
				<div class="flex items-center justify-between gap-3">
					<h2 class="text-sm font-semibold text-muted-foreground">{m.doc_versions_recent()}</h2>
					<div class="flex items-center gap-3">
						{#if canEdit && !addingVersion}
							<button
								type="button"
								onclick={openVersionUpload}
								class="rounded-md border border-border px-3 py-1 text-xs font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring"
							>
								{m.btn_upload_new_version()}
							</button>
						{/if}
						<a href="/documents/{docId}/versions"
							class="text-xs text-primary hover:underline focus:outline-2 focus:outline-ring">
							{m.doc_versions_view_all()}
						</a>
					</div>
				</div>

				{#if addingVersion}
					<form
						onsubmit={(e) => { e.preventDefault(); uploadNewVersion(); }}
						class="mt-3 space-y-3 rounded-md border border-primary/20 bg-muted/30 p-3"
					>
						<div>
							<label for="new-version-file" class="block text-xs font-medium text-muted-foreground">
								{m.label_file()} <span class="text-destructive">*</span>
							</label>
							<input
								id="new-version-file"
								type="file"
								onchange={onVersionFileSelected}
								disabled={versionUploading}
								required
								class="mt-1 w-full rounded-md border border-border bg-background px-3 py-1.5 text-sm file:mr-3 file:rounded-md file:border-0 file:bg-primary file:px-3 file:py-1 file:text-xs file:font-medium file:text-primary-foreground hover:file:opacity-90 focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
							/>
							{#if newVersionFile}
								<p class="mt-1 text-xs text-muted-foreground">
									{newVersionFile.name} ({formatSize(newVersionFile.size)})
								</p>
							{/if}
						</div>
						<div>
							<label for="new-version-summary" class="block text-xs font-medium text-muted-foreground">
								{m.label_change_summary()} <span class="font-normal">({m.upload_optional()})</span>
							</label>
							<textarea
								id="new-version-summary"
								bind:value={newVersionSummary}
								disabled={versionUploading}
								rows="2"
								maxlength="1000"
								placeholder={m.doc_version_change_placeholder()}
								class="mt-1 w-full rounded-md border border-border bg-background px-3 py-1.5 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
							></textarea>
						</div>
						{#if versionError}
							<p role="alert" class="text-xs text-destructive">{versionError}</p>
						{/if}
						<div class="flex justify-end gap-2">
							<button
								type="button"
								onclick={cancelVersionUpload}
								disabled={versionUploading}
								class="rounded-md border border-border px-3 py-1.5 text-xs font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
							>
								{m.btn_cancel()}
							</button>
							<button
								type="submit"
								disabled={versionUploading || !newVersionFile}
								class="rounded-md bg-primary px-3 py-1.5 text-xs font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50 disabled:cursor-not-allowed"
							>
								{versionUploading ? m.doc_version_uploading() : m.doc_version_upload_btn()}
							</button>
						</div>
					</form>
				{/if}
				{#if versions.length === 0}
					<p class="mt-2 text-sm text-muted-foreground">{m.doc_versions_empty()}</p>
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
									{#if ver.ocrApplied}
										<span
											class="rounded-md bg-accent/20 px-1.5 py-0.5 text-xs font-medium text-accent-foreground"
											title="OCR processed ({ver.ocrLanguage ?? 'eng'})"
										>
											OCR
										</span>
									{/if}
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
				<h2 class="text-sm font-semibold text-muted-foreground">{m.doc_status_heading()}</h2>
				<div class="mt-2 flex items-center gap-2">
					<StatusBadge status={doc.status} />
					{#if doc.checkedOut}
						<StatusBadge status="LOCKED" />
					{/if}
				</div>

				{#if availableTransitions.length > 0 && canEdit}
					<div class="mt-4 space-y-2">
						<h3 class="text-xs font-semibold uppercase tracking-wider text-muted-foreground">{m.label_actions()}</h3>
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
				<h2 class="mb-3 text-sm font-semibold text-muted-foreground">{m.label_details()}</h2>
				<dl class="space-y-2 text-sm">
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
						<dt class="text-muted-foreground">{m.label_storage()}</dt>
						<dd class="font-medium">{doc.storageMode}</dd>
					</div>
					<div>
						<dt class="text-muted-foreground">{m.label_workflow()}</dt>
						<dd class="font-medium">{doc.workflowType}</dd>
					</div>
					{#if doc.currentVersion}
						<div>
							<dt class="text-muted-foreground">{m.label_current_version()}</dt>
							<dd class="font-medium">v{doc.currentVersion.versionNumber}</dd>
						</div>
					{/if}
					<div>
						<dt class="text-muted-foreground">{m.label_created()}</dt>
						<dd class="font-medium">{formatDate(doc.createdAt)}</dd>
					</div>
					<div>
						<dt class="text-muted-foreground">{m.label_updated()}</dt>
						<dd class="font-medium">{formatDate(doc.updatedAt)}</dd>
					</div>
				</dl>
			</section>

			<!-- Share links -->
			{#if doc.status === 'PUBLISHED' || shareLinks.length > 0}
				<section class="rounded-lg border border-border bg-card p-5">
					<div class="flex items-center justify-between">
						<h2 class="text-sm font-semibold text-muted-foreground">
							{m.doc_share_heading()} ({shareLinks.filter((l) => !l.revoked).length})
						</h2>
						{#if doc.status === 'PUBLISHED' && versions.length > 0}
							<button
								type="button"
								onclick={openShareModal}
								class="rounded-md border border-border px-2 py-1 text-xs font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring"
							>
								{m.btn_share()}
							</button>
						{/if}
					</div>
					{#if shareLinks.length === 0}
						<p class="mt-2 text-xs text-muted-foreground">{m.doc_share_no_links()}</p>
					{:else}
						<ul class="mt-3 space-y-2">
							{#each shareLinks as link}
								<li class="rounded-md border border-border px-3 py-2 text-xs">
									<div class="flex items-center justify-between">
										<span>
											v{link.versionNumber}
											{#if link.hasPassword}
												<span title="Password protected">🔒</span>
											{/if}
										</span>
										{#if link.revoked}
											<span class="text-destructive">{m.doc_share_revoked()}</span>
										{:else}
											<button
												type="button"
												onclick={() => revokeShareLink(link.id)}
												class="text-destructive underline hover:opacity-80"
											>
												{m.doc_share_revoke()}
											</button>
										{/if}
									</div>
									<p class="text-muted-foreground">
										Expires {formatDate(link.expiresAt)}
										· {link.accessCount}{link.maxAccess ? `/${link.maxAccess}` : ''} views
										· by {link.createdByName}
									</p>
								</li>
							{/each}
						</ul>
					{/if}
				</section>
			{/if}

			<!-- Signatures -->
			<section class="rounded-lg border border-border bg-card p-5">
				<div class="flex items-center justify-between">
					<h2 class="text-sm font-semibold text-muted-foreground">
						{m.doc_sign_heading()} ({signatures.length})
					</h2>
					{#if doc.status !== 'LEGAL_HOLD' && versions.length > 0}
						<button
							type="button"
							onclick={openSignModal}
							class="rounded-md border border-border px-2 py-1 text-xs font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring"
						>
							{m.doc_sign_btn()}
						</button>
					{/if}
				</div>
				{#if signatures.length === 0}
					<p class="mt-2 text-xs text-muted-foreground">{m.doc_sign_no_signatures()}</p>
				{:else}
					<ul class="mt-3 space-y-2">
						{#each signatures as sig}
							<li class="rounded-md border border-border px-3 py-2 text-xs">
								<div class="flex items-center justify-between">
									<span class="font-medium">{sig.signerName}</span>
									<span class="text-muted-foreground">{formatDate(sig.signedAt)}</span>
								</div>
								<p class="text-muted-foreground">
									Signed as "{sig.typedName}" · v{sig.versionNumber}
								</p>
							</li>
						{/each}
					</ul>
				{/if}
			</section>

			<!-- Danger zone -->
			{#if hasAnyRole('GLOBAL_ADMIN', 'DEPT_ADMIN')}
				<section class="rounded-lg border border-destructive/30 bg-card p-5">
					<h2 class="text-sm font-semibold text-destructive">{m.doc_danger_zone()}</h2>
					<button onclick={deleteDocument} disabled={actionLoading}
						class="mt-3 w-full rounded-md border border-destructive bg-destructive/10 px-3 py-2 text-sm font-medium text-destructive hover:bg-destructive/20 focus:outline-2 focus:outline-ring disabled:opacity-50">
						{m.doc_delete_document()}
					</button>
				</section>
			{/if}
		</div>
	</div>

	<!-- Share link modal -->
	{#if showShareModal}
		<div
			class="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
			role="dialog"
			aria-modal="true"
			aria-labelledby="share-title"
		>
			<div class="w-full max-w-md rounded-lg border border-border bg-card p-6 shadow-lg">
				<h2 id="share-title" class="text-lg font-semibold">{m.doc_share_document()}</h2>

				{#if !createdShareToken}
					<p class="mt-2 text-sm text-muted-foreground">
						{m.doc_share_create_desc()}
					</p>
					<div class="mt-4 space-y-3">
						<div>
							<label for="share-expiry" class="block text-xs font-medium text-muted-foreground">
								{m.doc_share_expiry_label()}
							</label>
							<input
								id="share-expiry"
								type="number"
								min="1"
								max="90"
								bind:value={shareExpiryDays}
								disabled={creatingShare}
								class="mt-1 w-24 rounded-md border border-border bg-background px-3 py-1.5 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
							/>
						</div>
						<div>
							<label for="share-password" class="block text-xs font-medium text-muted-foreground">
								{m.doc_share_password_label()} <span class="font-normal">({m.upload_optional()})</span>
							</label>
							<input
								id="share-password"
								type="text"
								bind:value={sharePassword}
								disabled={creatingShare}
								placeholder={m.doc_share_password_placeholder()}
								class="mt-1 w-full rounded-md border border-border bg-background px-3 py-1.5 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
							/>
						</div>
						<div>
							<label for="share-max" class="block text-xs font-medium text-muted-foreground">
								{m.doc_share_max_views_label()} <span class="font-normal">({m.doc_share_max_views_hint()})</span>
							</label>
							<input
								id="share-max"
								type="number"
								min="1"
								bind:value={shareMaxAccess}
								disabled={creatingShare}
								placeholder="Unlimited"
								class="mt-1 w-24 rounded-md border border-border bg-background px-3 py-1.5 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
							/>
						</div>
					</div>
					{#if shareError}
						<p class="mt-3 text-sm text-destructive" role="alert">{shareError}</p>
					{/if}
					<div class="mt-5 flex justify-end gap-2">
						<button type="button" onclick={closeShareModal} disabled={creatingShare}
							class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50">
							{m.btn_cancel()}
						</button>
						<button type="button" onclick={createShareLink} disabled={creatingShare}
							class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50">
							{creatingShare ? m.doc_share_creating() : m.doc_share_create_link()}
						</button>
					</div>
				{:else}
					<p class="mt-2 text-sm text-muted-foreground">
						{m.doc_share_created_msg()}
					</p>
					<div class="mt-4 rounded-md border border-border bg-muted p-3">
						<p class="break-all font-mono text-xs select-all">
							{window.location.origin}/share/{createdShareToken}
						</p>
					</div>
					<div class="mt-5 flex justify-end gap-2">
						<button type="button" onclick={copyShareUrl}
							class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring">
							{m.doc_share_copy_url()}
						</button>
						<button type="button" onclick={closeShareModal}
							class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-offset-2 focus:outline-ring">
							{m.btn_done()}
						</button>
					</div>
				{/if}
			</div>
		</div>
	{/if}

	<!-- Sign modal -->
	{#if showSignModal}
		<div
			class="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
			role="dialog"
			aria-modal="true"
			aria-labelledby="sign-title"
		>
			<div class="w-full max-w-md rounded-lg border border-border bg-card p-6 shadow-lg">
				<h2 id="sign-title" class="text-lg font-semibold">{m.doc_sign_document()}</h2>
				<p class="mt-2 text-sm text-muted-foreground">
					{@html m.doc_sign_desc({ version: String(versions[0]?.versionNumber ?? '?') })}
				</p>

				<div class="mt-4">
					<label for="sign-name" class="block text-sm font-medium">
						{m.doc_sign_label()}
					</label>
					<input
						id="sign-name"
						type="text"
						bind:value={signTypedName}
						disabled={signing}
						maxlength="300"
						placeholder={m.doc_sign_placeholder()}
						class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
					/>
				</div>

				{#if signError}
					<p class="mt-3 text-sm text-destructive" role="alert">{signError}</p>
				{/if}

				<div class="mt-5 flex justify-end gap-2">
					<button
						type="button"
						onclick={closeSignModal}
						disabled={signing}
						class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
					>
						{m.btn_cancel()}
					</button>
					<button
						type="button"
						onclick={submitSignature}
						disabled={signing || !signTypedName.trim()}
						class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
					>
						{signing ? m.doc_sign_signing() : m.doc_sign_btn()}
					</button>
				</div>
			</div>
		</div>
	{/if}
{/if}
