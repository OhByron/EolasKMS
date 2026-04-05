<script lang="ts">
	import { onMount } from 'svelte';
	import { page } from '$app/state';
	import { api } from '$lib/api';
	import type { Department, UserProfile, DepartmentScanSettings } from '$lib/types/api';
	import PageHeader from '$lib/components/kosha/PageHeader.svelte';
	import StatusBadge from '$lib/components/kosha/StatusBadge.svelte';
	import ErrorBoundary from '$lib/components/kosha/ErrorBoundary.svelte';
	import UserCreateModal from '$lib/components/kosha/UserCreateModal.svelte';
	import UserTransferModal from '$lib/components/kosha/UserTransferModal.svelte';
	import WorkflowEditor from '$lib/components/kosha/WorkflowEditor.svelte';

	let dept = $state<Department | null>(null);
	let users = $state<UserProfile[]>([]);
	let usersTotal = $state(0);
	let scanSettings = $state<DepartmentScanSettings | null>(null);
	let loading = $state(true);
	let error = $state('');
	let addMemberModalOpen = $state(false);
	let savingScan = $state(false);
	let scanSaveResult = $state('');

	// Team tab state
	// savingUserId tracks which row has an in-flight update so we can disable
	// its controls; teamError holds the most recent error message for screen
	// readers. Only one row can be mutating at a time in the current UX but
	// the model allows any subset.
	let savingUserIds = $state<Set<string>>(new Set());
	let teamError = $state('');
	let transferModalOpen = $state(false);
	let userToTransfer = $state<UserProfile | null>(null);

	// Form state for the scan interval selector
	// 'inherit' sentinel distinguishes "fall back to global" from a numeric override
	let scanSelection = $state<'inherit' | number>('inherit');

	// Legal review toggle state
	// GLOBAL_ADMIN can flip this to flag a department as a legal review
	// provider. DEPT_ADMIN sees it read-only. In dev the authority check
	// is stubbed, so the toggle is always live.
	let savingLegalReview = $state(false);
	let legalReviewError = $state('');

	const deptId = $derived(page.params.id ?? '');

	onMount(() => loadData());

	async function loadData() {
		if (!deptId) return;
		loading = true;
		error = '';
		try {
			const [deptRes, usersRes, scanRes] = await Promise.all([
				api.departments.get(deptId),
				api.departments.users(deptId, 0, 50),
				api.notificationSettings.getDepartmentScan(deptId),
			]);
			dept = deptRes.data;
			users = usersRes.data;
			usersTotal = usersRes.meta?.total ?? users.length;
			scanSettings = scanRes.data;
			scanSelection = scanSettings.scanIntervalHours ?? 'inherit';
		} catch (e: any) {
			error = e.message;
		} finally {
			loading = false;
		}
	}

	async function changeRole(user: UserProfile, newRole: string) {
		if (newRole === user.role) return;
		await updateUser(user, { role: newRole });
	}

	async function toggleStatus(user: UserProfile) {
		const newStatus = user.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE';
		await updateUser(user, { status: newStatus });
	}

	function openTransfer(user: UserProfile) {
		userToTransfer = user;
		transferModalOpen = true;
	}

	function onUserTransferred(_updated: UserProfile) {
		// Reload the member list — the transferred user is no longer in this
		// department and should disappear from the table.
		loadData();
	}

	async function updateUser(user: UserProfile, patch: { role?: string; status?: string }) {
		const next = new Set(savingUserIds);
		next.add(user.id);
		savingUserIds = next;
		teamError = '';

		try {
			const res = await api.users.update(user.id, patch);
			// Update the local row in place so we don't re-fetch the whole list
			const idx = users.findIndex((u) => u.id === user.id);
			if (idx >= 0) {
				users[idx] = res.data;
				users = [...users];
			}
		} catch (e: any) {
			teamError = `Failed to update ${user.displayName}: ${e.message}`;
		} finally {
			const done = new Set(savingUserIds);
			done.delete(user.id);
			savingUserIds = done;
		}
	}

	async function saveScanSettings() {
		if (!deptId) return;
		savingScan = true;
		scanSaveResult = '';
		try {
			const res = await api.notificationSettings.updateDepartmentScan(deptId, {
				scanIntervalHours: scanSelection === 'inherit' ? null : scanSelection,
			});
			scanSettings = res.data;
			scanSelection = scanSettings.scanIntervalHours ?? 'inherit';
			scanSaveResult = 'Saved';
		} catch (e: any) {
			scanSaveResult = `Error: ${e.message}`;
		} finally {
			savingScan = false;
		}
	}

	function formatDate(iso: string | null): string {
		if (!iso) return 'never';
		return new Date(iso).toLocaleString('en-US');
	}

	/**
	 * Toggle the department's `handlesLegalReview` flag. Goes through the
	 * existing `PATCH /api/v1/departments/{id}` endpoint (JWT-authenticated).
	 * The backend enforces GLOBAL_ADMIN authority; the stubbed check in dev
	 * means the call always succeeds for any logged-in user.
	 */
	async function toggleLegalReview(next: boolean) {
		if (!dept) return;
		savingLegalReview = true;
		legalReviewError = '';
		try {
			const res = await api.departments.update(dept.id, {
				handlesLegalReview: next,
			});
			dept = res.data;
		} catch (e: any) {
			legalReviewError = e.message ?? 'Failed to update';
		} finally {
			savingLegalReview = false;
		}
	}
</script>

<svelte:head>
	<title>{dept?.name ?? 'Department'} - Administration - Eòlas</title>
</svelte:head>

{#if loading}
	<p aria-live="polite" class="text-muted-foreground">Loading department...</p>
{:else if error && !dept}
	<ErrorBoundary {error} onRetry={loadData} />
{:else if dept}
	<PageHeader title={dept.name} description={dept.description ?? undefined}>
		<a href="/admin/departments" class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-ring">
			← All Departments
		</a>
		<button
			type="button"
			onclick={() => (addMemberModalOpen = true)}
			class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-offset-2 focus:outline-ring"
		>
			+ Add Member
		</button>
	</PageHeader>

	<UserCreateModal
		bind:open={addMemberModalOpen}
		lockedDepartmentId={deptId}
		lockedDepartmentName={dept.name}
		onCreated={() => loadData()}
	/>

	<UserTransferModal
		bind:open={transferModalOpen}
		user={userToTransfer}
		onTransferred={onUserTransferred}
	/>

	<div class="mt-6 grid gap-6 lg:grid-cols-3">
		<div class="lg:col-span-2">
			<!-- Team Members tab -->
			<section class="rounded-lg border border-border bg-card p-5">
				<div class="flex items-center justify-between">
					<h2 class="text-sm font-semibold text-muted-foreground">Team Members ({usersTotal})</h2>
				</div>

				{#if teamError}
					<div
						role="alert"
						class="mt-3 rounded-md border border-destructive bg-destructive/10 p-3 text-sm text-destructive"
					>
						{teamError}
					</div>
				{/if}

				{#if users.length === 0}
					<p class="mt-3 text-sm text-muted-foreground">No users in this department yet.</p>
					<button
						type="button"
						onclick={() => (addMemberModalOpen = true)}
						class="mt-2 text-sm text-primary underline hover:opacity-80 focus:outline-2 focus:outline-ring"
					>
						Add the first team member
					</button>
				{:else}
					<div class="mt-3 overflow-x-auto">
						<table class="w-full text-sm" aria-label="Department members">
							<thead>
								<tr class="border-b border-border">
									<th scope="col" class="pb-2 text-left font-semibold">Name</th>
									<th scope="col" class="pb-2 text-left font-semibold">Email</th>
									<th scope="col" class="pb-2 text-left font-semibold">Role</th>
									<th scope="col" class="pb-2 text-left font-semibold">Status</th>
									<th scope="col" class="pb-2 text-right font-semibold">Actions</th>
								</tr>
							</thead>
							<tbody>
								{#each users as u (u.id)}
									{@const busy = savingUserIds.has(u.id)}
									<tr class="border-b border-border last:border-0" class:opacity-60={u.status === 'INACTIVE'}>
										<td class="py-2">
											<a href="/admin/users/{u.id}" class="font-medium text-primary hover:underline focus:outline-2 focus:outline-ring">
												{u.displayName}
											</a>
										</td>
										<td class="py-2 text-muted-foreground">{u.email}</td>
										<td class="py-2">
											<label class="sr-only" for="role-{u.id}">Role for {u.displayName}</label>
											<select
												id="role-{u.id}"
												value={u.role}
												onchange={(e) => changeRole(u, (e.target as HTMLSelectElement).value)}
												disabled={busy}
												class="rounded-md border border-border bg-background px-2 py-1 text-xs focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
											>
												<option value="CONTRIBUTOR">Contributor</option>
												<option value="EDITOR">Editor</option>
												<option value="DEPT_ADMIN">Dept Admin</option>
												<option value="GLOBAL_ADMIN">Global Admin</option>
											</select>
										</td>
										<td class="py-2">
											<StatusBadge status={u.status} />
										</td>
										<td class="py-2 text-right">
											<div class="flex justify-end gap-1">
												<button
													type="button"
													onclick={() => toggleStatus(u)}
													disabled={busy}
													class="rounded-md border border-border px-2 py-1 text-xs font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
													aria-label={u.status === 'ACTIVE' ? `Deactivate ${u.displayName}` : `Reactivate ${u.displayName}`}
												>
													{u.status === 'ACTIVE' ? 'Deactivate' : 'Reactivate'}
												</button>
												<button
													type="button"
													onclick={() => openTransfer(u)}
													disabled={busy}
													class="rounded-md border border-border px-2 py-1 text-xs font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
													aria-label="Transfer {u.displayName} to another department"
												>
													Transfer…
												</button>
											</div>
										</td>
									</tr>
								{/each}
							</tbody>
						</table>
					</div>
					<p class="mt-3 text-xs text-muted-foreground">
						Deactivating a member removes their rights without deleting their account.
						Transferring moves them to another department — they will lose access to documents scoped to {dept.name}.
					</p>
				{/if}
			</section>

			<div class="mt-6">
				<WorkflowEditor departmentId={deptId} departmentMembers={users} />
			</div>
		</div>

		<div class="space-y-4">
			<section class="rounded-lg border border-border bg-card p-5">
				<h2 class="mb-3 text-sm font-semibold text-muted-foreground">Details</h2>
				<dl class="space-y-2 text-sm">
					<div>
						<dt class="text-muted-foreground">Status</dt>
						<dd><StatusBadge status={dept.status} /></dd>
					</div>
					<div>
						<dt class="text-muted-foreground">Created</dt>
						<dd class="font-medium">{new Date(dept.createdAt).toLocaleDateString()}</dd>
					</div>
					<div>
						<dt class="text-muted-foreground">Updated</dt>
						<dd class="font-medium">{new Date(dept.updatedAt).toLocaleDateString()}</dd>
					</div>
				</dl>
			</section>

			<section class="rounded-lg border border-border bg-card p-5">
				<h2 class="mb-1 text-sm font-semibold text-muted-foreground">Legal Review</h2>
				<p class="mb-3 text-xs text-muted-foreground">
					Controls whether members of this department can be picked as legal reviewers for documents that require one.
				</p>
				<label class="flex items-start gap-2 text-sm">
					<input
						type="checkbox"
						checked={dept.handlesLegalReview}
						disabled={savingLegalReview}
						onchange={(e) => toggleLegalReview((e.target as HTMLInputElement).checked)}
						class="mt-0.5 focus:ring-ring"
					/>
					<span>
						Handles legal review
						<span class="block text-xs text-muted-foreground">
							Global admin only. Members of flagged departments appear in the "Legal reviewer" dropdown on the upload form.
						</span>
					</span>
				</label>
				{#if legalReviewError}
					<p class="mt-2 text-xs text-destructive" role="alert">{legalReviewError}</p>
				{/if}
			</section>

			{#if scanSettings}
				<section class="rounded-lg border border-border bg-card p-5">
					<h2 class="mb-1 text-sm font-semibold text-muted-foreground">Retention Scan Cadence</h2>
					<p class="mb-3 text-xs text-muted-foreground">
						How often this department is scanned for approaching or overdue retention reviews.
					</p>

					<fieldset>
						<legend class="sr-only">Scan interval</legend>
						<div class="space-y-2">
							<label class="flex items-center gap-2 text-sm">
								<input
									type="radio"
									name="scan-interval"
									checked={scanSelection === 'inherit'}
									onchange={() => (scanSelection = 'inherit')}
								/>
								<span>
									Use global default
									<span class="text-xs text-muted-foreground">
										({scanSettings.inheritsDefault ? scanSettings.effectiveIntervalHours : scanSettings.validIntervals[0].hours}h)
									</span>
								</span>
							</label>
							{#each scanSettings.validIntervals as opt}
								<label class="flex items-center gap-2 text-sm">
									<input
										type="radio"
										name="scan-interval"
										checked={scanSelection === opt.hours}
										onchange={() => (scanSelection = opt.hours)}
									/>
									<span>
										{opt.label}
										<span class="text-xs text-muted-foreground">({opt.hours}h)</span>
									</span>
								</label>
							{/each}
						</div>
					</fieldset>

					<div class="mt-4 space-y-2 border-t border-border pt-3 text-xs">
						<div class="flex justify-between">
							<dt class="text-muted-foreground">Current</dt>
							<dd class="font-medium">
								{scanSettings.effectiveIntervalHours}h
								{#if scanSettings.inheritsDefault}
									<span class="text-muted-foreground">(inherited)</span>
								{/if}
							</dd>
						</div>
						<div class="flex justify-between">
							<dt class="text-muted-foreground">Last scan</dt>
							<dd class="font-medium">{formatDate(scanSettings.lastScanAt)}</dd>
						</div>
					</div>

					<div class="mt-4 flex items-center justify-between">
						{#if scanSaveResult}
							<p
								class="text-xs"
								class:text-success={!scanSaveResult.startsWith('Error')}
								class:text-destructive={scanSaveResult.startsWith('Error')}
								aria-live="polite"
							>
								{scanSaveResult}
							</p>
						{:else}
							<span></span>
						{/if}
						<button
							type="button"
							onclick={saveScanSettings}
							disabled={savingScan}
							class="rounded-md bg-primary px-3 py-1.5 text-xs font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
						>
							{savingScan ? 'Saving...' : 'Save'}
						</button>
					</div>
				</section>
			{/if}
		</div>
	</div>
{/if}
