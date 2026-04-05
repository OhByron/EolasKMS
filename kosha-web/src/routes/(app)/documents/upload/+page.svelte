<script lang="ts">
	import { goto } from '$app/navigation';
	import { api } from '$lib/api';
	import type { Department, DocumentDetail, VersionDetail, UserProfile } from '$lib/types/api';
	import { onMount } from 'svelte';
	import { user as authUser } from '$lib/auth';
	import PageHeader from '$lib/components/kosha/PageHeader.svelte';

	// --- State ---
	let step = $state(1);
	let departments = $state<Department[]>([]);
	let loading = $state(false);
	let error = $state('');

	// Step 1: Details
	let title = $state('');
	let description = $state('');
	let departmentId = $state('');
	let storageMode = $state('VAULT');
	let workflowType = $state('NONE');

	// Ownership
	let ownerMode = $state<'self' | 'other'>('self');
	let ownerSearch = $state('');
	let ownerResults = $state<UserProfile[]>([]);
	let selectedOwner = $state<UserProfile | null>(null);
	let searchingOwner = $state(false);

	// Step 2: File
	let selectedFile = $state<File | null>(null);
	let uploading = $state(false);
	let uploadProgress = $state(0);

	// Step 3: AI review
	let createdDoc = $state<DocumentDetail | null>(null);
	let createdVersion = $state<VersionDetail | null>(null);
	let aiStatus = $state<'idle' | 'processing' | 'complete' | 'error'>('idle');
	let aiStep = $state('');
	let pollCount = $state(0);

	// Duplicate detection
	let duplicateInfo = $state<{
		duplicate: boolean;
		existingDocumentId?: string;
		existingTitle?: string;
		existingVersion?: string;
		existingStatus?: string;
		contentHash?: string;
	} | null>(null);
	let checkingDuplicate = $state(false);

	onMount(async () => {
		try {
			const res = await api.departments.list(0, 100);
			departments = res.data;
		} catch (e: any) {
			error = e.message;
		}
	});

	// --- Ownership ---
	let ownerSearchTimeout: ReturnType<typeof setTimeout>;

	async function searchOwners(query: string) {
		ownerSearch = query;
		clearTimeout(ownerSearchTimeout);
		if (query.length < 2) { ownerResults = []; return; }
		ownerSearchTimeout = setTimeout(async () => {
			searchingOwner = true;
			try {
				const res = await api.users.list(0, 20);
				// Client-side filter since we don't have a search endpoint yet
				ownerResults = res.data.filter((u: UserProfile) =>
					u.displayName.toLowerCase().includes(query.toLowerCase()) ||
					u.email.toLowerCase().includes(query.toLowerCase())
				);
			} catch { ownerResults = []; }
			finally { searchingOwner = false; }
		}, 300);
	}

	function selectOwner(u: UserProfile) {
		selectedOwner = u;
		ownerSearch = '';
		ownerResults = [];
	}

	function clearOwner() {
		selectedOwner = null;
		ownerMode = 'self';
	}

	// --- Step 1 validation ---
	const step1Valid = $derived(title.trim().length > 0 && departmentId.length > 0);

	// --- Step 2: file handling ---
	function handleFileDrop(e: DragEvent) {
		e.preventDefault();
		const file = e.dataTransfer?.files?.[0];
		if (file) {
			selectedFile = file;
			duplicateInfo = null;
			checkForDuplicate(file);
		}
	}

	function handleFileSelect(e: Event) {
		const input = e.target as HTMLInputElement;
		const file = input.files?.[0];
		if (file) {
			selectedFile = file;
			duplicateInfo = null;
			checkForDuplicate(file);
		}
	}

	function removeFile() {
		selectedFile = null;
		duplicateInfo = null;
	}

	async function checkForDuplicate(file: File) {
		checkingDuplicate = true;
		try {
			const formData = new FormData();
			formData.append('file', file);

			const currentUser = (await import('$lib/auth')).user;
			let token = '';
			const unsub = currentUser.subscribe((u) => { token = u?.accessToken ?? ''; });
			unsub();

			const res = await fetch('/api/v1/documents/check-duplicate', {
				method: 'POST',
				headers: { Authorization: `Bearer ${token}` },
				body: formData,
			});
			if (res.ok) {
				const data = await res.json();
				duplicateInfo = data.data;
			}
		} catch {
			// Duplicate check failed — proceed without it
		} finally {
			checkingDuplicate = false;
		}
	}

	function goToExistingDocument() {
		if (duplicateInfo?.existingDocumentId) {
			window.location.href = `/documents/${duplicateInfo.existingDocumentId}`;
		}
	}

	// --- Step 2 → 3: Create document + version ---
	async function submitUpload() {
		if (!selectedFile) return;
		uploading = true;
		error = '';
		try {
			// Create document
			const docRes = await api.documents.create({
				title,
				description: description || undefined,
				departmentId,
				storageMode,
				workflowType,
				ownerId: ownerMode === 'other' && selectedOwner ? selectedOwner.id : undefined,
			});
			createdDoc = docRes.data;

			uploadProgress = 20;

			// Create version record
			const verRes = await api.documents.createVersion(createdDoc.id, {
				fileName: selectedFile.name,
				fileSizeBytes: selectedFile.size,
				changeSummary: 'Initial upload'
			});
			createdVersion = verRes.data;
			uploadProgress = 40;

			// Upload the actual file for Tika text extraction + AI processing
			const formData = new FormData();
			formData.append('file', selectedFile);

			const currentUser = (await import('$lib/auth')).user;
			let token = '';
			const unsub = currentUser.subscribe((u) => { token = u?.accessToken ?? ''; });
			unsub();

			const uploadRes = await fetch(
				`/api/v1/documents/${createdDoc.id}/versions/${createdVersion.id}/upload`,
				{
					method: 'POST',
					headers: { Authorization: `Bearer ${token}` },
					body: formData,
				}
			);
			if (!uploadRes.ok) {
				const err = await uploadRes.json().catch(() => ({ detail: 'Upload failed' }));
				throw new Error(err.detail ?? `Upload failed: HTTP ${uploadRes.status}`);
			}
			uploadProgress = 100;

			// Move to AI step
			step = 3;
			startAiPolling();
		} catch (e: any) {
			error = e.message;
		} finally {
			uploading = false;
		}
	}

	// --- Step 3: AI processing poll ---
	function startAiPolling() {
		aiStatus = 'processing';
		aiStep = 'Parsing document...';
		pollCount = 0;
		pollAiStatus();
	}

	async function pollAiStatus() {
		if (!createdDoc) return;
		pollCount++;

		if (pollCount <= 2) aiStep = 'Parsing document...';
		else if (pollCount <= 4) aiStep = 'Generating summary...';
		else if (pollCount <= 6) aiStep = 'Extracting keywords...';
		else aiStep = 'Classifying document...';

		try {
			const res = await api.documents.get(createdDoc.id);
			createdDoc = res.data;

			const verRes = await api.documents.versions(createdDoc.id);
			if (verRes.data.length > 0) {
				createdVersion = verRes.data[0];
				if (createdVersion.metadata?.summary) {
					aiStatus = 'complete';
					return;
				}
			}
		} catch (e: any) {
			// Stop polling on hard errors (404, 401, etc.)
			if (e.message?.includes('404') || e.message?.includes('401')) {
				aiStatus = 'complete';
				return;
			}
		}

		// Max 15 attempts (~45s), then stop
		if (pollCount < 15 && aiStatus === 'processing') {
			setTimeout(pollAiStatus, 3000);
		} else if (aiStatus === 'processing') {
			aiStatus = 'complete';
		}
	}

	async function saveAsDraft() {
		if (!createdDoc) return;
		goto(`/documents/${createdDoc.id}`);
	}

	async function submitForReview() {
		if (!createdDoc) return;
		try {
			await api.documents.update(createdDoc.id, { status: 'IN_REVIEW' });
			goto(`/documents/${createdDoc.id}`);
		} catch (e: any) {
			error = e.message;
		}
	}
</script>

<svelte:head>
	<title>Upload Document - Kosha</title>
</svelte:head>

<PageHeader title="Upload Document" />

<!-- Step indicator -->
<nav aria-label="Upload steps" class="mt-6">
	<ol class="flex items-center gap-2 text-sm">
		{#each [{ n: 1, label: 'Details' }, { n: 2, label: 'Upload' }, { n: 3, label: 'Review' }] as s}
			<li class="flex items-center gap-2">
				<span
					class="flex h-7 w-7 items-center justify-center rounded-full text-xs font-bold"
					class:bg-primary={step >= s.n}
					class:text-primary-foreground={step >= s.n}
					class:bg-muted={step < s.n}
					class:text-muted-foreground={step < s.n}
					aria-current={step === s.n ? 'step' : undefined}
				>
					{s.n}
				</span>
				<span class:font-medium={step === s.n} class:text-muted-foreground={step !== s.n}>
					{s.label}
				</span>
				{#if s.n < 3}
					<span class="mx-2 h-px w-8 bg-border" aria-hidden="true"></span>
				{/if}
			</li>
		{/each}
	</ol>
</nav>

{#if error}
	<div role="alert" class="mt-4 rounded-md border border-destructive bg-destructive/10 p-3 text-sm text-destructive">
		{error}
		<button onclick={() => (error = '')} class="ml-2 underline focus:outline-2 focus:outline-ring">Dismiss</button>
	</div>
{/if}

<div class="mt-6 max-w-2xl">
	<!-- STEP 1: Details -->
	{#if step === 1}
		<form onsubmit={(e) => { e.preventDefault(); if (step1Valid) step = 2; }} class="space-y-4">
			<div>
				<label for="doc-title" class="block text-sm font-medium">Title <span class="text-destructive">*</span></label>
				<input
					id="doc-title"
					type="text"
					bind:value={title}
					required
					maxlength="500"
					class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
					aria-required="true"
				/>
			</div>

			<div>
				<label for="doc-desc" class="block text-sm font-medium">Description</label>
				<textarea
					id="doc-desc"
					bind:value={description}
					rows="3"
					maxlength="5000"
					class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
				></textarea>
			</div>

			<div>
				<label for="doc-dept" class="block text-sm font-medium">Department <span class="text-destructive">*</span></label>
				<select
					id="doc-dept"
					bind:value={departmentId}
					required
					class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
					aria-required="true"
				>
					<option value="">Select a department</option>
					{#each departments as dept}
						<option value={dept.id}>{dept.name}</option>
					{/each}
				</select>
			</div>

			<fieldset>
				<legend class="text-sm font-medium">Storage Mode</legend>
				<div class="mt-1 flex gap-4">
					<label class="flex items-center gap-2 text-sm">
						<input type="radio" bind:group={storageMode} value="VAULT" class="focus:ring-ring" />
						Vault (managed storage)
					</label>
					<label class="flex items-center gap-2 text-sm">
						<input type="radio" bind:group={storageMode} value="CONNECTOR" class="focus:ring-ring" />
						Connector (index in place)
					</label>
				</div>
			</fieldset>

			<!-- Document Owner -->
			<fieldset>
				<legend class="text-sm font-medium">Document Owner</legend>
				<div class="mt-1 flex gap-4">
					<label class="flex items-center gap-2 text-sm">
						<input type="radio" bind:group={ownerMode} value="self" onchange={clearOwner} class="focus:ring-ring" />
						Myself
					</label>
					<label class="flex items-center gap-2 text-sm">
						<input type="radio" bind:group={ownerMode} value="other" class="focus:ring-ring" />
						Someone else
					</label>
				</div>

				{#if ownerMode === 'other'}
					<div class="mt-2">
						{#if selectedOwner}
							<div class="flex items-center gap-2 rounded-md border border-border bg-muted/50 px-3 py-2">
								<span class="text-sm font-medium">{selectedOwner.displayName}</span>
								<span class="text-xs text-muted-foreground">{selectedOwner.email}</span>
								<button
									type="button"
									onclick={clearOwner}
									class="ml-auto text-xs text-destructive hover:underline focus:outline-2 focus:outline-ring"
								>Remove</button>
							</div>
							<p class="mt-1 text-xs text-muted-foreground">
								You will be set as the proxy owner for this document.
							</p>
						{:else}
							<label for="owner-search" class="sr-only">Search for a user</label>
							<input
								id="owner-search"
								type="text"
								placeholder="Search by name or email..."
								value={ownerSearch}
								oninput={(e) => searchOwners((e.target as HTMLInputElement).value)}
								class="w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
								autocomplete="off"
							/>
							{#if searchingOwner}
								<p class="mt-1 text-xs text-muted-foreground">Searching...</p>
							{/if}
							{#if ownerResults.length > 0}
								<ul class="mt-1 max-h-40 overflow-y-auto rounded-md border border-border bg-card" role="listbox" aria-label="User search results">
									{#each ownerResults as u}
										<li>
											<button
												type="button"
												class="flex w-full items-center gap-2 px-3 py-2 text-left text-sm hover:bg-muted focus:bg-muted focus:outline-2 focus:outline-offset-[-2px] focus:outline-ring"
												onclick={() => selectOwner(u)}
												role="option"
												aria-selected="false"
											>
												<span class="font-medium">{u.displayName}</span>
												<span class="text-xs text-muted-foreground">{u.email}</span>
												<span class="ml-auto text-xs text-muted-foreground">{u.role}</span>
											</button>
										</li>
									{/each}
								</ul>
							{:else if ownerSearch.length >= 2 && !searchingOwner}
								<p class="mt-1 text-xs text-muted-foreground">No users found</p>
							{/if}
						{/if}
					</div>
				{/if}
			</fieldset>

			<div>
				<label for="doc-wf" class="block text-sm font-medium">Workflow</label>
				<select
					id="doc-wf"
					bind:value={workflowType}
					class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
				>
					<option value="NONE">No workflow</option>
					<option value="LINEAR">Linear review chain</option>
					<option value="PARALLEL">Parallel review</option>
				</select>
			</div>

			<div class="flex justify-end gap-3 pt-4">
				<a
					href="/documents"
					class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring"
				>
					Cancel
				</a>
				<button
					type="submit"
					disabled={!step1Valid}
					class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50 disabled:cursor-not-allowed"
				>
					Next: Upload File →
				</button>
			</div>
		</form>
	{/if}

	<!-- STEP 2: File Upload -->
	{#if step === 2}
		<div class="space-y-4">
			{#if !selectedFile}
				<!-- Drop zone -->
				<!-- svelte-ignore a11y_no_static_element_interactions -->
				<div
					class="flex flex-col items-center justify-center rounded-lg border-2 border-dashed border-border p-12 text-center transition hover:border-primary"
					ondragover={(e) => e.preventDefault()}
					ondrop={handleFileDrop}
				>
					<p class="text-lg font-medium">Drag & drop your file here</p>
					<p class="mt-1 text-sm text-muted-foreground">or click to browse</p>
					<p class="mt-2 text-xs text-muted-foreground">
						Supports: Word, Excel, PowerPoint, PDF, Text, Images
					</p>
					<label class="mt-4 cursor-pointer rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus-within:outline-2 focus-within:outline-offset-2 focus-within:outline-ring">
						Choose File
						<input
							type="file"
							class="sr-only"
							accept=".pdf,.doc,.docx,.xls,.xlsx,.pptx,.txt,.png,.jpg,.jpeg,.tiff"
							onchange={handleFileSelect}
						/>
					</label>
				</div>
			{:else}
				<!-- Selected file -->
				<div class="flex items-center justify-between rounded-lg border border-border p-4">
					<div>
						<p class="font-medium">{selectedFile.name}</p>
						<p class="text-sm text-muted-foreground">
							{(selectedFile.size / 1048576).toFixed(1)} MB
						</p>
					</div>
					<button
						onclick={removeFile}
						class="rounded-md border border-border px-3 py-1 text-sm hover:bg-muted focus:outline-2 focus:outline-ring"
						aria-label="Remove selected file"
					>
						Remove
					</button>
				</div>
			{/if}

			{#if checkingDuplicate}
				<p class="text-sm text-muted-foreground" aria-live="polite">Checking for duplicates...</p>
			{/if}

			{#if duplicateInfo?.duplicate}
				<div class="rounded-lg border-2 border-warning bg-warning/10 p-4" role="alert">
					<p class="font-medium text-warning">Duplicate file detected</p>
					<p class="mt-1 text-sm">
						This file already exists as <strong>v{duplicateInfo.existingVersion}</strong> of
						"<strong>{duplicateInfo.existingTitle}</strong>"
						(status: {duplicateInfo.existingStatus}).
					</p>
					<div class="mt-3 flex gap-2">
						<button
							onclick={goToExistingDocument}
							class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-ring"
						>
							Go to existing document
						</button>
						<button
							onclick={() => (duplicateInfo = null)}
							class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-ring"
						>
							Upload anyway
						</button>
					</div>
				</div>
			{/if}

			{#if uploading}
				<div>
					<div
						class="h-2 overflow-hidden rounded-full bg-muted"
						role="progressbar"
						aria-valuenow={uploadProgress}
						aria-valuemin={0}
						aria-valuemax={100}
						aria-label="Upload progress"
					>
						<div
							class="h-full rounded-full bg-primary transition-all duration-300"
							style="width: {uploadProgress}%"
						></div>
					</div>
					<p class="mt-1 text-sm text-muted-foreground" aria-live="polite">
						Uploading... {uploadProgress}%
					</p>
				</div>
			{/if}

			<div class="flex justify-end gap-3 pt-4">
				<button
					onclick={() => (step = 1)}
					disabled={uploading}
					class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
				>
					← Back
				</button>
				<button
					onclick={submitUpload}
					disabled={!selectedFile || uploading}
					class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50 disabled:cursor-not-allowed"
				>
					{uploading ? 'Uploading...' : 'Next: AI Review →'}
				</button>
			</div>
		</div>
	{/if}

	<!-- STEP 3: AI Processing & Review -->
	{#if step === 3}
		<div class="space-y-6">
			<!-- AI Status -->
			<div
				role="status"
				aria-live="polite"
				aria-atomic="true"
				class="rounded-lg border border-border bg-card p-5"
			>
				{#if aiStatus === 'processing'}
					<div class="flex items-center gap-3">
						<span class="inline-block h-5 w-5 animate-spin rounded-full border-2 border-primary border-t-transparent" aria-hidden="true"></span>
						<div>
							<p class="font-medium">AI is analyzing your document...</p>
							<p class="text-sm text-muted-foreground">{aiStep}</p>
						</div>
					</div>
				{:else if aiStatus === 'complete'}
					<p class="font-medium text-success">AI analysis complete. Review the results below.</p>
				{/if}
			</div>

			{#if aiStatus === 'complete' && createdVersion}
				<!-- AI Summary -->
				<section class="rounded-lg border border-border bg-card p-5">
					<h2 class="text-sm font-semibold text-muted-foreground">AI Summary</h2>
					{#if createdVersion.metadata?.summary}
						<p class="mt-2 text-sm leading-relaxed">{createdVersion.metadata.summary}</p>
						{#if createdVersion.metadata.aiConfidence != null}
							<div class="mt-3">
								<div class="flex items-center justify-between text-xs text-muted-foreground">
									<span>Confidence</span>
									<span>{Math.round(createdVersion.metadata.aiConfidence * 100)}%</span>
								</div>
								<div
									class="mt-1 h-2 overflow-hidden rounded-full bg-muted"
									role="progressbar"
									aria-valuenow={Math.round(createdVersion.metadata.aiConfidence * 100)}
									aria-valuemin={0}
									aria-valuemax={100}
									aria-label="AI confidence"
								>
									<div
										class="h-full rounded-full bg-success transition-all"
										style="width: {createdVersion.metadata.aiConfidence * 100}%"
									></div>
								</div>
							</div>
						{/if}
					{:else}
						<p class="mt-2 text-sm text-muted-foreground">
							No AI summary generated. You can add one manually on the document detail page.
						</p>
					{/if}
				</section>
			{/if}

			<!-- Actions -->
			<div class="flex justify-end gap-3 pt-4">
				<button
					onclick={saveAsDraft}
					class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring"
				>
					Save as Draft
				</button>
				<button
					onclick={submitForReview}
					class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-offset-2 focus:outline-ring"
				>
					Submit for Review
				</button>
			</div>
		</div>
	{/if}
</div>
