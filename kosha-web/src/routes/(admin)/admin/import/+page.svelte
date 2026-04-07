<script lang="ts">
	import { api } from '$lib/api';
	import type {
		ImportDryRunResponse,
		ImportDryRunRow,
		UserImportDryRunResponse,
		UserImportDryRunRow,
	} from '$lib/types/api';
	import PageHeader from '$lib/components/kosha/PageHeader.svelte';
	import * as m from '$paraglide/messages';

	/**
	 * Bulk import validator and interactive fixer (roadmap 4.2.3).
	 *
	 * This page is a browser-based companion to the `kosha-import` CLI.
	 * It doesn't run imports — that stays in the CLI where file bytes
	 * live on disk. What it does is close the pain-point loop that
	 * made CLI-only painful for first-time users: authoring a valid
	 * CSV without trial-and-error.
	 *
	 * ## Workflow
	 *
	 * 1. Admin picks a tab (Documents or Users)
	 * 2. Pastes a CSV into the textarea
	 * 3. Clicks "Validate" — calls the backend dry-run endpoint
	 * 4. Results table highlights per-row errors
	 * 5. For the document tab, an "Enable auto-provision" toggle
	 *    re-runs validation with the auto-provision flag set, turning
	 *    owner-not-found rows into "will be created on import"
	 * 6. Once the CSV is clean, "Copy to clipboard" lets the admin
	 *    paste it into their CLI invocation
	 *
	 * No backend writes happen from this page — the dry-run endpoints
	 * are purely validation. The real import still runs through the CLI.
	 *
	 * ## Why not full in-browser import
	 *
	 * For user imports, browser-native would work cleanly (no file
	 * bytes to ship). For document imports, the files live on disk
	 * and moving them through the browser for 10k-row imports is an
	 * ordeal — folder picker APIs, memory pressure, upload progress,
	 * retries. Keeping the CLI as the only import runner avoids that
	 * complexity entirely. A future 4.2.3+ pass could add browser-
	 * native user imports (smaller scope) if demand materialises.
	 */

	type Tab = 'documents' | 'users';

	let tab = $state<Tab>('documents');

	// Shared state
	let csvInput = $state('');
	let validating = $state(false);
	let error = $state('');

	// Document mode state
	let docResult = $state<ImportDryRunResponse | null>(null);
	let autoProvision = $state(false);

	// User mode state
	let userResult = $state<UserImportDryRunResponse | null>(null);

	function switchTab(next: Tab) {
		tab = next;
		docResult = null;
		userResult = null;
		error = '';
	}

	async function validate() {
		if (!csvInput.trim()) {
			error = m.err_csv_empty();
			return;
		}
		validating = true;
		error = '';
		try {
			if (tab === 'documents') {
				const res = await api.importValidator.validateDocuments(csvInput, autoProvision);
				docResult = res.data;
				userResult = null;
			} else {
				const res = await api.importValidator.validateUsers(csvInput);
				userResult = res.data;
				docResult = null;
			}
		} catch (e: any) {
			error = e.message ?? 'Validation failed';
		} finally {
			validating = false;
		}
	}

	async function copyToClipboard() {
		try {
			await navigator.clipboard.writeText(csvInput);
			// Visual confirmation is nice-to-have; for v1 we just reuse
			// the error state as a transient status message.
			error = m.err_clipboard_success();
			setTimeout(() => {
				if (error === m.err_clipboard_success()) error = '';
			}, 2000);
		} catch {
			error = m.err_clipboard_failed();
		}
	}

	function onFilePick(ev: Event) {
		const input = ev.target as HTMLInputElement;
		const file = input.files?.[0];
		if (!file) return;
		const reader = new FileReader();
		reader.onload = () => {
			csvInput = String(reader.result ?? '');
		};
		reader.readAsText(file);
	}

	function templateForDocuments(): string {
		return [
			'file_path,title,description,department_name,category_name,owner_email,tags,requires_legal_review,legal_reviewer_email',
			'./policies/travel.pdf,"Travel Policy","Company travel rules","Finance","Policy","maria@example.com","travel;expense",false,',
			'./contracts/acme.docx,"Acme MSA","Master services agreement","Legal","Contract","anne@example.com","contract",true,"legal@example.com"',
		].join('\n');
	}

	function templateForUsers(): string {
		return [
			'email,display_name,department_name,role,temporary_password',
			'alice@example.com,Alice Adams,Finance,EDITOR,',
			'bob@example.com,Bob Baker,Marketing,CONTRIBUTOR,BobTempPass-1',
		].join('\n');
	}

	function loadTemplate() {
		csvInput = tab === 'documents' ? templateForDocuments() : templateForUsers();
		docResult = null;
		userResult = null;
	}
</script>

<svelte:head>
	<title>{m.import_title()} - {m.nav_sidebar_administration()} - {m.nav_app_title()}</title>
</svelte:head>

<PageHeader
	title={m.import_title()}
	description={m.import_desc()}
/>

<div class="mt-6 max-w-5xl space-y-6">
	<!-- Tab switcher -->
	<div role="tablist" aria-label="Import mode" class="flex gap-2 border-b border-border">
		<button
			role="tab"
			aria-selected={tab === 'documents'}
			onclick={() => switchTab('documents')}
			class="rounded-t-md px-4 py-2 text-sm font-medium transition focus:outline-2 focus:outline-offset-2 focus:outline-ring"
			class:bg-primary={tab === 'documents'}
			class:text-primary-foreground={tab === 'documents'}
			class:text-muted-foreground={tab !== 'documents'}
		>
			{m.import_tab_documents()}
		</button>
		<button
			role="tab"
			aria-selected={tab === 'users'}
			onclick={() => switchTab('users')}
			class="rounded-t-md px-4 py-2 text-sm font-medium transition focus:outline-2 focus:outline-offset-2 focus:outline-ring"
			class:bg-primary={tab === 'users'}
			class:text-primary-foreground={tab === 'users'}
			class:text-muted-foreground={tab !== 'users'}
		>
			{m.import_tab_users()}
		</button>
	</div>

	<!-- Description for the active tab -->
	<aside class="rounded-md border border-border bg-muted/30 p-4 text-sm">
		{#if tab === 'documents'}
			<p>
				Document CSVs reference files on disk via <code class="text-xs">file_path</code> and
				pre-existing users via <code class="text-xs">owner_email</code>. Validate here, then run
				the real import with the <code class="text-xs">kosha-import</code> CLI against your
				document directory. See <a href="/admin/import-docs" class="text-primary underline">the
				import runbook</a> for the full column reference.
			</p>
			<p class="mt-2">
				Enable <strong>auto-provision</strong> below to allow the importer to create missing
				owners on the fly with temporary passwords, matching the <code class="text-xs"
					>--auto-provision</code> CLI flag.
			</p>
		{:else}
			<p>
				User CSVs provision Eòlas accounts and Keycloak accounts in one atomic step. Run the user
				import <em>before</em> the document import if your documents reference users that don't
				exist yet (alternatively, use auto-provision on the document import).
			</p>
			<div class="mt-4 overflow-x-auto">
				<table class="w-full text-xs">
					<thead>
						<tr class="border-b border-border text-left">
							<th class="pb-1.5 pr-3 font-medium">Column</th>
							<th class="pb-1.5 pr-3 font-medium">Required</th>
							<th class="pb-1.5 font-medium">Notes</th>
						</tr>
					</thead>
					<tbody class="text-muted-foreground">
						<tr class="border-b border-border/50">
							<td class="py-1.5 pr-3"><code>email</code></td>
							<td class="py-1.5 pr-3">Yes</td>
							<td class="py-1.5">Must be unique across both Eòlas and Keycloak</td>
						</tr>
						<tr class="border-b border-border/50">
							<td class="py-1.5 pr-3"><code>display_name</code></td>
							<td class="py-1.5 pr-3">Yes</td>
							<td class="py-1.5">Full name as it appears in the UI</td>
						</tr>
						<tr class="border-b border-border/50">
							<td class="py-1.5 pr-3"><code>department_name</code></td>
							<td class="py-1.5 pr-3">Yes</td>
							<td class="py-1.5">Exact match, case-insensitive; department must exist</td>
						</tr>
						<tr class="border-b border-border/50">
							<td class="py-1.5 pr-3"><code>role</code></td>
							<td class="py-1.5 pr-3">Yes</td>
							<td class="py-1.5">GLOBAL_ADMIN, DEPT_ADMIN, EDITOR, or CONTRIBUTOR</td>
						</tr>
						<tr>
							<td class="py-1.5 pr-3"><code>temporary_password</code></td>
							<td class="py-1.5 pr-3">No</td>
							<td class="py-1.5">Blank generates a secure random password automatically</td>
						</tr>
					</tbody>
				</table>
			</div>
			<p class="mt-3 text-xs text-muted-foreground">
				Click <strong>Load template</strong> above for a ready-to-edit example CSV.
			</p>
		{/if}
	</aside>

	<!-- CSV input -->
	<section class="rounded-lg border border-border bg-card p-5">
		<div class="flex items-center justify-between gap-3">
			<h2 class="text-sm font-semibold">{m.import_csv_heading()}</h2>
			<div class="flex items-center gap-2">
				<input
					type="file"
					accept=".csv,text/csv"
					onchange={onFilePick}
					class="text-xs file:mr-2 file:rounded-md file:border-0 file:bg-primary file:px-3 file:py-1 file:text-xs file:font-medium file:text-primary-foreground hover:file:opacity-90"
				/>
				<button
					type="button"
					onclick={loadTemplate}
					class="rounded-md border border-border px-3 py-1 text-xs font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring"
				>
					{m.btn_load_template()}
				</button>
			</div>
		</div>
		<textarea
			bind:value={csvInput}
			rows="10"
			class="mt-3 w-full rounded-md border border-border bg-background px-3 py-2 font-mono text-xs focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
			placeholder={m.import_csv_placeholder()}
		></textarea>

		<div class="mt-4 flex items-center justify-between gap-3">
			<div class="flex items-center gap-4">
				{#if tab === 'documents'}
					<label class="flex items-center gap-2 text-sm">
						<input type="checkbox" bind:checked={autoProvision} />
						{m.import_autoprovision()}
					</label>
				{/if}
			</div>
			<div class="flex gap-2">
				<button
					type="button"
					onclick={copyToClipboard}
					class="rounded-md border border-border px-3 py-1.5 text-xs font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring"
					disabled={!csvInput.trim()}
				>
					{m.btn_copy_clipboard()}
				</button>
				<button
					type="button"
					onclick={validate}
					disabled={validating || !csvInput.trim()}
					class="rounded-md bg-primary px-4 py-1.5 text-xs font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
				>
					{validating ? m.btn_validating() : m.btn_validate()}
				</button>
			</div>
		</div>

		{#if error}
			<p
				class="mt-3 text-sm"
				class:text-success={error === m.err_clipboard_success()}
				class:text-destructive={error !== m.err_clipboard_success()}
				aria-live="polite"
			>
				{error}
			</p>
		{/if}
	</section>

	<!-- Results: documents -->
	{#if tab === 'documents' && docResult}
		<section class="rounded-lg border border-border bg-card p-5">
			<h2 class="text-sm font-semibold">
				{m.import_doc_results({ valid: docResult.validRows.toString(), total: docResult.totalRows.toString() })}
				{#if docResult.invalidRows > 0}
					<span class="text-destructive">{m.import_with_errors({ count: docResult.invalidRows.toString() })}</span>
				{:else}
					<span class="text-success">{m.import_all_good()}</span>
				{/if}
			</h2>

			{#if docResult.globalErrors.length > 0}
				<div class="mt-3 rounded-md border border-destructive bg-destructive/10 p-3 text-sm text-destructive">
					<p class="font-semibold">{m.import_csv_errors()}</p>
					<ul class="mt-1 list-disc pl-5">
						{#each docResult.globalErrors as err}
							<li>{err}</li>
						{/each}
					</ul>
				</div>
			{/if}

			<div class="mt-3 overflow-x-auto">
				<table class="w-full text-sm">
					<thead>
						<tr class="border-b border-border bg-muted/50">
							<th scope="col" class="px-3 py-2 text-left">{m.import_col_row()}</th>
							<th scope="col" class="px-3 py-2 text-left">{m.import_col_file()}</th>
							<th scope="col" class="px-3 py-2 text-left">{m.import_col_status()}</th>
							<th scope="col" class="px-3 py-2 text-left">{m.import_col_notes()}</th>
						</tr>
					</thead>
					<tbody>
						{#each docResult.rows as row}
							<tr class="border-b border-border">
								<td class="px-3 py-2 font-mono text-xs">{row.row}</td>
								<td class="px-3 py-2 font-mono text-xs">{row.filePath}</td>
								<td class="px-3 py-2">
									{#if row.ok && row.autoProvisionOwner}
										<span class="rounded-md bg-warning/20 px-2 py-0.5 text-xs text-warning">
											{m.import_status_auto()}
										</span>
									{:else if row.ok}
										<span class="rounded-md bg-success/20 px-2 py-0.5 text-xs text-success">
											{m.import_status_ok()}
										</span>
									{:else}
										<span class="rounded-md bg-destructive/20 px-2 py-0.5 text-xs text-destructive">
											{m.import_status_error()}
										</span>
									{/if}
								</td>
								<td class="px-3 py-2 text-xs">
									{#if row.errors.length > 0}
										<ul class="list-disc pl-4 text-destructive">
											{#each row.errors as err}
												<li>{err}</li>
											{/each}
										</ul>
									{:else if row.autoProvisionOwner}
										<span class="text-muted-foreground">
											will create
											<span class="font-mono">{row.autoProvisionOwner.email}</span>
											({row.autoProvisionOwner.displayName}) as CONTRIBUTOR
										</span>
									{:else}
										<span class="text-muted-foreground">-</span>
									{/if}
								</td>
							</tr>
						{/each}
					</tbody>
				</table>
			</div>

			{#if docResult.invalidRows === 0}
				<div class="mt-4 rounded-md border border-success/30 bg-success/10 p-3 text-sm">
					<p class="font-medium text-success">{m.import_ready()}</p>
					<p class="mt-1 text-xs text-muted-foreground">
						Copy it to your clipboard, save it next to your document directory, and run:
					</p>
					<pre class="mt-2 overflow-x-auto rounded-md bg-background p-2 text-xs">java -jar kosha-import.jar \
  --csv manifest.csv \
  --root /path/to/docs \
  --api-url {window.location.origin} \
  --token "$KOSHA_ADMIN_TOKEN"{autoProvision ? ' \\\n  --auto-provision' : ''}</pre>
				</div>
			{/if}
		</section>
	{/if}

	<!-- Results: users -->
	{#if tab === 'users' && userResult}
		<section class="rounded-lg border border-border bg-card p-5">
			<h2 class="text-sm font-semibold">
				{m.import_doc_results({ valid: userResult.validRows.toString(), total: userResult.totalRows.toString() })}
				{#if userResult.invalidRows > 0}
					<span class="text-destructive">{m.import_with_errors({ count: userResult.invalidRows.toString() })}</span>
				{:else}
					<span class="text-success">{m.import_all_good()}</span>
				{/if}
			</h2>

			{#if userResult.globalErrors.length > 0}
				<div class="mt-3 rounded-md border border-destructive bg-destructive/10 p-3 text-sm text-destructive">
					<p class="font-semibold">{m.import_csv_errors()}</p>
					<ul class="mt-1 list-disc pl-5">
						{#each userResult.globalErrors as err}
							<li>{err}</li>
						{/each}
					</ul>
				</div>
			{/if}

			<div class="mt-3 overflow-x-auto">
				<table class="w-full text-sm">
					<thead>
						<tr class="border-b border-border bg-muted/50">
							<th scope="col" class="px-3 py-2 text-left">{m.import_col_row()}</th>
							<th scope="col" class="px-3 py-2 text-left">{m.import_col_email()}</th>
							<th scope="col" class="px-3 py-2 text-left">{m.import_col_status()}</th>
							<th scope="col" class="px-3 py-2 text-left">{m.import_col_notes()}</th>
						</tr>
					</thead>
					<tbody>
						{#each userResult.rows as row}
							<tr class="border-b border-border">
								<td class="px-3 py-2 font-mono text-xs">{row.row}</td>
								<td class="px-3 py-2 font-mono text-xs">{row.email}</td>
								<td class="px-3 py-2">
									{#if row.ok}
										<span class="rounded-md bg-success/20 px-2 py-0.5 text-xs text-success">
											{m.import_status_ok()}
										</span>
									{:else}
										<span class="rounded-md bg-destructive/20 px-2 py-0.5 text-xs text-destructive">
											{m.import_status_error()}
										</span>
									{/if}
								</td>
								<td class="px-3 py-2 text-xs">
									{#if row.errors.length > 0}
										<ul class="list-disc pl-4 text-destructive">
											{#each row.errors as err}
												<li>{err}</li>
											{/each}
										</ul>
									{:else}
										<span class="text-muted-foreground">-</span>
									{/if}
								</td>
							</tr>
						{/each}
					</tbody>
				</table>
			</div>

			{#if userResult.invalidRows === 0}
				<div class="mt-4 rounded-md border border-success/30 bg-success/10 p-3 text-sm">
					<p class="font-medium text-success">{m.import_ready_users()}</p>
					<p class="mt-1 text-xs text-muted-foreground">
						Copy it to your clipboard and run:
					</p>
					<pre class="mt-2 overflow-x-auto rounded-md bg-background p-2 text-xs">java -jar kosha-import.jar \
  --mode users \
  --csv users.csv \
  --api-url {window.location.origin} \
  --token "$KOSHA_ADMIN_TOKEN"</pre>
				</div>
			{/if}
		</section>
	{/if}
</div>
