<script lang="ts">
	import { onMount } from 'svelte';
	import { page } from '$app/state';
	import { api } from '$lib/api';
	import type { Department, UserProfile } from '$lib/types/api';
	import PageHeader from '$lib/components/kosha/PageHeader.svelte';
	import StatusBadge from '$lib/components/kosha/StatusBadge.svelte';
	import ErrorBoundary from '$lib/components/kosha/ErrorBoundary.svelte';

	/**
	 * Global-admin user detail / edit page.
	 *
	 * Fields that can be changed here:
	 *   - display name
	 *   - role
	 *   - status (active/inactive)
	 *   - department
	 *
	 * Email is intentionally read-only. Changing it would require a matching
	 * update in Keycloak (username is the email) and we don't have that sync
	 * implemented — a DB-only edit would silently break login. Leaving it
	 * visible but disabled keeps the intent clear; deliberate renames can be
	 * handled via a dedicated flow later.
	 *
	 * The "Reset password" action calls POST /users/{id}/reset-password which
	 * regenerates a temp password in Keycloak and fires an event for the
	 * notification module to email it to the user. The modal shows the new
	 * password to the admin too, as a fallback for email delivery failures.
	 */

	let userProfile = $state<UserProfile | null>(null);
	let departments = $state<Department[]>([]);
	let loading = $state(true);
	let error = $state('');

	// Edit drafts — kept separate from the loaded server data so we can
	// track dirty state without mutating the source of truth.
	let displayNameDraft = $state('');
	let roleDraft = $state('');
	let statusDraft = $state('');
	let departmentIdDraft = $state('');

	let savingProfile = $state(false);
	let saveMessage = $state('');

	// Reset password modal state
	let resetOpen = $state(false);
	let resetting = $state(false);
	let resetResult = $state<{ password: string; email: string } | null>(null);
	let resetError = $state('');

	const userId = $derived(page.params.id ?? '');

	const isDirty = $derived.by(() => {
		if (!userProfile) return false;
		return (
			displayNameDraft.trim() !== userProfile.displayName ||
			roleDraft !== userProfile.role ||
			statusDraft !== userProfile.status ||
			departmentIdDraft !== userProfile.departmentId
		);
	});

	onMount(() => loadAll());

	async function loadAll() {
		loading = true;
		error = '';
		try {
			const [userRes, deptRes] = await Promise.all([
				api.users.get(userId),
				api.departments.list(0, 200),
			]);
			userProfile = userRes.data;
			departments = deptRes.data;
			seedDrafts(userRes.data);
		} catch (e: any) {
			error = e.message;
		} finally {
			loading = false;
		}
	}

	function seedDrafts(u: UserProfile) {
		displayNameDraft = u.displayName;
		roleDraft = u.role;
		statusDraft = u.status;
		departmentIdDraft = u.departmentId;
	}

	async function saveProfile() {
		if (!userProfile || !isDirty) return;
		savingProfile = true;
		saveMessage = '';
		try {
			// Only send the fields that actually changed so we don't poke
			// Keycloak-sync'd fields (email) accidentally.
			const patch: Record<string, string> = {};
			if (displayNameDraft.trim() !== userProfile.displayName) {
				patch.displayName = displayNameDraft.trim();
			}
			if (roleDraft !== userProfile.role) patch.role = roleDraft;
			if (statusDraft !== userProfile.status) patch.status = statusDraft;
			if (departmentIdDraft !== userProfile.departmentId) {
				patch.departmentId = departmentIdDraft;
			}

			const res = await api.users.update(userId, patch);
			userProfile = res.data;
			seedDrafts(res.data);
			saveMessage = 'Saved';
			setTimeout(() => {
				if (saveMessage === 'Saved') saveMessage = '';
			}, 2500);
		} catch (e: any) {
			saveMessage = `Error: ${e.message ?? 'Save failed'}`;
		} finally {
			savingProfile = false;
		}
	}

	function revertDrafts() {
		if (userProfile) seedDrafts(userProfile);
		saveMessage = '';
	}

	function openReset() {
		resetOpen = true;
		resetError = '';
		resetResult = null;
	}

	function closeReset() {
		if (resetting) return;
		resetOpen = false;
		resetResult = null;
		resetError = '';
	}

	async function confirmReset() {
		if (!userProfile) return;
		resetting = true;
		resetError = '';
		try {
			const res = await api.users.resetPassword(userId);
			resetResult = {
				password: res.data.temporaryPassword,
				email: res.data.user.email,
			};
		} catch (e: any) {
			resetError = e.message ?? 'Failed to reset password';
		} finally {
			resetting = false;
		}
	}

	async function copyPassword() {
		if (!resetResult) return;
		try {
			await navigator.clipboard.writeText(resetResult.password);
			saveMessage = 'Password copied to clipboard';
			setTimeout(() => (saveMessage = ''), 2000);
		} catch {
			// Ignore — user can select the text manually.
		}
	}
</script>

<svelte:head>
	<title>{userProfile?.displayName ?? 'User'} - Administration - Eòlas</title>
</svelte:head>

{#if loading}
	<p aria-live="polite" class="text-muted-foreground">Loading user...</p>
{:else if error && !userProfile}
	<ErrorBoundary {error} onRetry={loadAll} />
{:else if userProfile}
	<PageHeader title={userProfile.displayName} description={userProfile.email}>
		<a
			href="/admin/users"
			class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring"
		>
			← All Users
		</a>
		<button
			type="button"
			onclick={openReset}
			class="rounded-md border border-border bg-background px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring"
		>
			Reset password
		</button>
	</PageHeader>

	<div class="mt-6 grid gap-6 lg:grid-cols-3">
		<div class="space-y-4 lg:col-span-2">
			<section class="rounded-lg border border-border bg-card p-5">
				<h2 class="text-sm font-semibold text-muted-foreground">Profile</h2>

				<div class="mt-4 space-y-4">
					<div>
						<label for="u-name" class="block text-xs font-medium text-muted-foreground">
							Display name
						</label>
						<input
							id="u-name"
							type="text"
							bind:value={displayNameDraft}
							maxlength="200"
							disabled={savingProfile}
							class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
						/>
					</div>

					<div>
						<label for="u-email" class="block text-xs font-medium text-muted-foreground">
							Email <span class="font-normal">(read-only — change via Keycloak)</span>
						</label>
						<input
							id="u-email"
							type="email"
							value={userProfile.email}
							disabled
							class="mt-1 w-full rounded-md border border-border bg-muted px-3 py-2 text-sm text-muted-foreground"
						/>
					</div>

					<div class="grid gap-4 sm:grid-cols-2">
						<div>
							<label for="u-role" class="block text-xs font-medium text-muted-foreground">
								Role
							</label>
							<select
								id="u-role"
								bind:value={roleDraft}
								disabled={savingProfile}
								class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
							>
								<option value="CONTRIBUTOR">Contributor</option>
								<option value="EDITOR">Editor</option>
								<option value="DEPT_ADMIN">Department Admin</option>
								<option value="GLOBAL_ADMIN">Global Admin</option>
							</select>
						</div>

						<div>
							<label for="u-status" class="block text-xs font-medium text-muted-foreground">
								Status
							</label>
							<select
								id="u-status"
								bind:value={statusDraft}
								disabled={savingProfile}
								class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
							>
								<option value="ACTIVE">Active</option>
								<option value="INACTIVE">Inactive</option>
							</select>
						</div>
					</div>

					<div>
						<label for="u-dept" class="block text-xs font-medium text-muted-foreground">
							Department
						</label>
						<select
							id="u-dept"
							bind:value={departmentIdDraft}
							disabled={savingProfile}
							class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
						>
							{#each departments as d (d.id)}
								<option value={d.id}>{d.name}</option>
							{/each}
						</select>
					</div>
				</div>

				<div class="mt-5 flex items-center justify-between gap-3">
					{#if saveMessage}
						<p
							class="text-xs"
							class:text-success={!saveMessage.startsWith('Error')}
							class:text-destructive={saveMessage.startsWith('Error')}
							aria-live="polite"
						>
							{saveMessage}
						</p>
					{:else}
						<span></span>
					{/if}
					<div class="flex gap-2">
						<button
							type="button"
							onclick={revertDrafts}
							disabled={savingProfile || !isDirty}
							class="rounded-md border border-border px-3 py-1.5 text-xs font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50 disabled:cursor-not-allowed"
						>
							Revert
						</button>
						<button
							type="button"
							onclick={saveProfile}
							disabled={savingProfile || !isDirty}
							class="rounded-md bg-primary px-4 py-1.5 text-xs font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50 disabled:cursor-not-allowed"
						>
							{savingProfile ? 'Saving...' : 'Save changes'}
						</button>
					</div>
				</div>
			</section>
		</div>

		<div class="space-y-4">
			<section class="rounded-lg border border-border bg-card p-5">
				<h2 class="mb-3 text-sm font-semibold text-muted-foreground">Current</h2>
				<dl class="space-y-2 text-sm">
					<div>
						<dt class="text-muted-foreground">Department</dt>
						<dd class="font-medium">{userProfile.departmentName}</dd>
					</div>
					<div>
						<dt class="text-muted-foreground">Role</dt>
						<dd>
							<span
								class="rounded-md bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary"
								>{userProfile.role}</span
							>
						</dd>
					</div>
					<div>
						<dt class="text-muted-foreground">Status</dt>
						<dd><StatusBadge status={userProfile.status} /></dd>
					</div>
					<div>
						<dt class="text-muted-foreground">Joined</dt>
						<dd class="font-medium">{new Date(userProfile.createdAt).toLocaleDateString()}</dd>
					</div>
					<div>
						<dt class="text-muted-foreground">Last update</dt>
						<dd class="font-medium">{new Date(userProfile.updatedAt).toLocaleDateString()}</dd>
					</div>
				</dl>
			</section>
		</div>
	</div>

	{#if resetOpen}
		<!-- Lightweight inline modal — keeps the reset flow on-page without
			 pulling in a separate modal component. -->
		<div
			class="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
			role="dialog"
			aria-modal="true"
			aria-labelledby="reset-title"
		>
			<div class="w-full max-w-md rounded-lg border border-border bg-card p-6 shadow-lg">
				<h2 id="reset-title" class="text-lg font-semibold">Reset password</h2>

				{#if !resetResult}
					<p class="mt-2 text-sm text-muted-foreground">
						This will generate a new temporary password for <strong>{userProfile.displayName}</strong
						> ({userProfile.email}) and email it to them. They will be required to change it on next
						sign-in.
					</p>
					{#if resetError}
						<p class="mt-3 text-sm text-destructive" role="alert">{resetError}</p>
					{/if}
					<div class="mt-5 flex justify-end gap-2">
						<button
							type="button"
							onclick={closeReset}
							disabled={resetting}
							class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
						>
							Cancel
						</button>
						<button
							type="button"
							onclick={confirmReset}
							disabled={resetting}
							class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-offset-2 focus:outline-ring disabled:opacity-50"
						>
							{resetting ? 'Resetting...' : 'Reset and email'}
						</button>
					</div>
				{:else}
					<p class="mt-2 text-sm text-muted-foreground">
						Password reset. An email has been queued to <strong>{resetResult.email}</strong>. As a
						safety net, the new temporary password is shown below — share it through a secure
						channel if email delivery fails.
					</p>
					<div class="mt-4 rounded-md border border-border bg-muted p-3">
						<p class="font-mono text-sm break-all select-all">{resetResult.password}</p>
					</div>
					<div class="mt-5 flex justify-end gap-2">
						<button
							type="button"
							onclick={copyPassword}
							class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring"
						>
							Copy
						</button>
						<button
							type="button"
							onclick={closeReset}
							class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-offset-2 focus:outline-ring"
						>
							Done
						</button>
					</div>
				{/if}
			</div>
		</div>
	{/if}
{/if}
