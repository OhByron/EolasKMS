<script lang="ts">
	import { goto } from '$app/navigation';
	import { api } from '$lib/api';
	import type { Department, DocumentCategory, DocumentDetail, VersionDetail, UserProfile } from '$lib/types/api';
	import { onMount } from 'svelte';
	import { user as authUser } from '$lib/auth';
	import PageHeader from '$lib/components/kosha/PageHeader.svelte';
	import Markdown from '$lib/components/kosha/Markdown.svelte';
	import * as m from '$paraglide/messages';

	// --- State ---
	let step = $state(1);
	let departments = $state<Department[]>([]);
	let categories = $state<DocumentCategory[]>([]);
	let legalReviewers = $state<UserProfile[]>([]);
	let loading = $state(false);
	let error = $state('');

	// Step 1: Details
	let title = $state('');
	let description = $state('');
	let departmentId = $state('');
	let categoryId = $state('');
	let storageMode = $state('VAULT');
	let workflowType = $state('NONE');

	// Legal review
	let requiresLegalReview = $state(false);
	let legalReviewerId = $state('');
	// Tracks whether the user has manually overridden the category hint.
	// Once they untick a suggested default we don't re-tick it when they
	// switch categories (until they pick a fresh category that suggests it).
	let legalReviewUserEdited = $state(false);

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
			// Load reference data in parallel. Failures on categories or legal
			// reviewers are non-blocking — the rest of the form still works.
			// Departments are scoped to what the caller can actually upload into
			// (their home department, or everything for GLOBAL_ADMIN). The
			// server re-enforces this on POST so the picker is advisory only.
			const [deptRes, catRes, revRes] = await Promise.all([
				api.me.uploadableDepartments(),
				api.documentCategories.list().catch(() => ({ data: [] as DocumentCategory[] })),
				api.legalReview.listReviewers().catch(() => ({ data: [] as UserProfile[] })),
			]);
			departments = deptRes.data;
			// If the user has exactly one uploadable department, pre-select it —
			// that's the 99% case for non-admin contributors and spares them a
			// forced click on a dropdown with only one option.
			if (departments.length === 1) {
				departmentId = departments[0].id;
			}
			categories = catRes.data;
			legalReviewers = revRes.data;
		} catch (e: any) {
			error = e.message;
		}
	});

	/**
	 * When the user picks a category that suggests legal review, auto-tick
	 * the checkbox — but only if they haven't manually toggled it already.
	 * If they've overridden the default once (either direction), we respect
	 * their choice until they change categories to one where the hint
	 * matches their current state again.
	 */
	function onCategoryChange(newCatId: string) {
		categoryId = newCatId;
		if (legalReviewUserEdited) return;
		const cat = categories.find((c) => c.id === newCatId);
		if (cat && cat.suggestsLegalReview && !requiresLegalReview) {
			requiresLegalReview = true;
		}
	}

	function onLegalReviewToggle(next: boolean) {
		requiresLegalReview = next;
		legalReviewUserEdited = true;
		if (!next) {
			legalReviewerId = '';
		}
	}

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
	const step1Valid = $derived(
		title.trim().length > 0 &&
		departmentId.length > 0 &&
		(!requiresLegalReview || legalReviewerId.length > 0)
	);

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
				categoryId: categoryId || undefined,
				storageMode,
				workflowType,
				ownerId: ownerMode === 'other' && selectedOwner ? selectedOwner.id : undefined,
				requiresLegalReview,
				legalReviewerId: requiresLegalReview ? legalReviewerId : null,
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
	<title>{m.page_title_upload_doc()} - {m.nav_app_title()}</title>
</svelte:head>

<PageHeader title={m.upload_title()} />

<!-- Step indicator -->
<nav aria-label="Upload steps" class="mt-6">
	<ol class="flex items-center gap-2 text-sm">
		{#each [{ n: 1, label: m.upload_step_details() }, { n: 2, label: m.upload_step_upload() }, { n: 3, label: m.upload_step_review() }] as s}
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
		<button onclick={() => (error = '')} class="ml-2 underline focus:outline-2 focus:outline-ring">{m.btn_dismiss()}</button>
	</div>
{/if}

<div class="mt-6 max-w-2xl">
	<!-- STEP 1: Details -->
	{#if step === 1}
		<form onsubmit={(e) => { e.preventDefault(); if (step1Valid) step = 2; }} class="space-y-4">
			<div>
				<label for="doc-title" class="block text-sm font-medium">{m.upload_field_title()} <span class="text-destructive">*</span></label>
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
				<label for="doc-desc" class="block text-sm font-medium">{m.upload_field_description()}</label>
				<textarea
					id="doc-desc"
					bind:value={description}
					rows="3"
					maxlength="5000"
					class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
				></textarea>
			</div>

			<div>
				<label for="doc-dept" class="block text-sm font-medium">{m.upload_field_department()} <span class="text-destructive">*</span></label>
				<select
					id="doc-dept"
					bind:value={departmentId}
					required
					class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
					aria-required="true"
				>
					<option value="">{m.label_select_department()}</option>
					{#each departments as dept}
						<option value={dept.id}>{dept.name}</option>
					{/each}
				</select>
			</div>

			<div>
				<label for="doc-category" class="block text-sm font-medium">{m.upload_field_category()}</label>
				<select
					id="doc-category"
					value={categoryId}
					onchange={(e) => onCategoryChange((e.target as HTMLSelectElement).value)}
					class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
				>
					<option value="">{m.category_uncategorised()}</option>
					{#each categories as cat}
						<option value={cat.id}>
							{cat.name}{cat.suggestsLegalReview ? ` ${m.category_legal_review_suggested()}` : ''}
						</option>
					{/each}
				</select>
				<p class="mt-1 text-xs text-muted-foreground">
					{m.upload_category_hint()}
				</p>
			</div>

			<fieldset class="rounded-md border border-border bg-muted/20 p-3">
				<legend class="px-1 text-sm font-medium">{m.upload_legal_heading()}</legend>
				<label class="flex items-start gap-2 text-sm">
					<input
						type="checkbox"
						checked={requiresLegalReview}
						onchange={(e) => onLegalReviewToggle((e.target as HTMLInputElement).checked)}
						class="mt-0.5 focus:ring-ring"
					/>
					<span>
						{m.upload_legal_requires()}
						<span class="block text-xs text-muted-foreground">
							{m.upload_legal_hint()}
						</span>
					</span>
				</label>

				{#if requiresLegalReview}
					{#if legalReviewers.length === 0}
						<p class="mt-2 rounded-md border border-warning bg-warning/10 p-2 text-xs" role="alert">
							{m.upload_legal_unavailable()}
						</p>
					{:else}
						<div class="mt-3">
							<label for="legal-reviewer" class="block text-xs font-medium">
								{m.upload_legal_reviewer_label()} <span class="text-destructive">*</span>
							</label>
							<select
								id="legal-reviewer"
								bind:value={legalReviewerId}
								required={requiresLegalReview}
								class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
							>
								<option value="">Select a legal reviewer</option>
								{#each legalReviewers as reviewer}
									<option value={reviewer.id}>
										{reviewer.displayName}, {reviewer.departmentName}
									</option>
								{/each}
							</select>
							<p class="mt-1 text-xs text-muted-foreground">
								{m.upload_legal_email_hint()}
							</p>
						</div>
					{/if}
				{/if}
			</fieldset>

			<fieldset>
				<legend class="text-sm font-medium">{m.upload_field_storage()}</legend>
				<div class="mt-1 flex gap-4">
					<label class="flex items-center gap-2 text-sm">
						<input type="radio" bind:group={storageMode} value="VAULT" class="focus:ring-ring" />
						{m.upload_storage_vault()}
					</label>
					<label class="flex items-center gap-2 text-sm">
						<input type="radio" bind:group={storageMode} value="CONNECTOR" class="focus:ring-ring" />
						{m.upload_storage_connector()}
					</label>
				</div>
			</fieldset>

			<!-- Document Owner -->
			<fieldset>
				<legend class="text-sm font-medium">{m.upload_owner_heading()}</legend>
				<div class="mt-1 flex gap-4">
					<label class="flex items-center gap-2 text-sm">
						<input type="radio" bind:group={ownerMode} value="self" onchange={clearOwner} class="focus:ring-ring" />
						{m.upload_owner_myself()}
					</label>
					<label class="flex items-center gap-2 text-sm">
						<input type="radio" bind:group={ownerMode} value="other" class="focus:ring-ring" />
						{m.upload_owner_other()}
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
								>{m.btn_remove()}</button>
							</div>
							<p class="mt-1 text-xs text-muted-foreground">
								{m.upload_owner_proxy_hint()}
							</p>
						{:else}
							<label for="owner-search" class="sr-only">Search for a user</label>
							<input
								id="owner-search"
								type="text"
								placeholder={m.upload_owner_search_placeholder()}
								value={ownerSearch}
								oninput={(e) => searchOwners((e.target as HTMLInputElement).value)}
								class="w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
								autocomplete="off"
							/>
							{#if searchingOwner}
								<p class="mt-1 text-xs text-muted-foreground">{m.upload_searching()}</p>
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
								<p class="mt-1 text-xs text-muted-foreground">{m.upload_no_users_found()}</p>
							{/if}
						{/if}
					</div>
				{/if}
			</fieldset>

			<div>
				<label for="doc-wf" class="block text-sm font-medium">{m.upload_field_workflow()}</label>
				<select
					id="doc-wf"
					bind:value={workflowType}
					class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
				>
					<option value="NONE">{m.workflow_none()}</option>
					<option value="LINEAR">{m.workflow_linear()}</option>
					<option value="PARALLEL">{m.workflow_parallel()}</option>
				</select>
			</div>

			<div class="flex justify-end gap-3 pt-4">
				<a
					href="/documents"
					class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring"
				>
					{m.btn_cancel()}
				</a>
				<button
					type="submit"
					disabled={!step1Valid}
					class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50 disabled:cursor-not-allowed"
				>
					{m.doc_next_upload()}
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
					<p class="text-lg font-medium">{m.upload_file_drop()}</p>
					<p class="mt-1 text-sm text-muted-foreground">{m.upload_file_browse()}</p>
					<p class="mt-2 text-xs text-muted-foreground">
						{m.upload_file_formats()}
					</p>
					<label class="mt-4 cursor-pointer rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus-within:outline-2 focus-within:outline-offset-2 focus-within:outline-ring">
						{m.upload_file_button()}
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
						{m.btn_remove()}
					</button>
				</div>
			{/if}

			{#if checkingDuplicate}
				<p class="text-sm text-muted-foreground" aria-live="polite">{m.upload_duplicate_checking()}</p>
			{/if}

			{#if duplicateInfo?.duplicate}
				<div class="rounded-lg border-2 border-warning bg-warning/10 p-4" role="alert">
					<p class="font-medium text-warning">{m.upload_duplicate_detected()}</p>
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
							{m.btn_go_existing()}
						</button>
						<button
							onclick={() => (duplicateInfo = null)}
							class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-ring"
						>
							{m.btn_upload_anyway()}
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
						{m.btn_uploading()} {uploadProgress}%
					</p>
				</div>
			{/if}

			<div class="flex justify-end gap-3 pt-4">
				<button
					onclick={() => (step = 1)}
					disabled={uploading}
					class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
				>
					{m.btn_back()}
				</button>
				<button
					onclick={submitUpload}
					disabled={!selectedFile || uploading}
					class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50 disabled:cursor-not-allowed"
				>
					{uploading ? m.btn_uploading() : m.doc_next_ai_review()}
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
							<p class="font-medium">{m.upload_ai_analyzing()}</p>
							<p class="text-sm text-muted-foreground">{aiStep}</p>
						</div>
					</div>
				{:else if aiStatus === 'complete'}
					<p class="font-medium text-success">{m.upload_ai_complete()}</p>
				{/if}
			</div>

			{#if aiStatus === 'complete' && createdVersion}
				<!-- AI Summary -->
				<section class="rounded-lg border border-border bg-card p-5">
					<h2 class="text-sm font-semibold text-muted-foreground">{m.doc_ai_summary()}</h2>
					{#if createdVersion.metadata?.summary}
						<Markdown content={createdVersion.metadata.summary} class="mt-2 text-sm" />
						{#if createdVersion.metadata.aiConfidence != null}
							<div class="mt-3">
								<div class="flex items-center justify-between text-xs text-muted-foreground">
									<span>{m.versions_ai_confidence()}</span>
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
							{m.upload_ai_no_summary()}
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
					{m.upload_save_draft()}
				</button>
				<button
					onclick={submitForReview}
					class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-offset-2 focus:outline-ring"
				>
					{m.upload_submit_review()}
				</button>
			</div>
		</div>
	{/if}
</div>
