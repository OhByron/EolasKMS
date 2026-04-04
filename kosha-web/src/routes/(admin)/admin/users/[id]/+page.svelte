<script lang="ts">
	import { onMount } from 'svelte';
	import { page } from '$app/state';
	import { api } from '$lib/api';
	import type { UserProfile } from '$lib/types/api';
	import PageHeader from '$lib/components/kosha/PageHeader.svelte';
	import StatusBadge from '$lib/components/kosha/StatusBadge.svelte';
	import ErrorBoundary from '$lib/components/kosha/ErrorBoundary.svelte';

	let userProfile = $state<UserProfile | null>(null);
	let loading = $state(true);
	let error = $state('');
	let saving = $state(false);
	let editRole = $state('');

	const userId = $derived(page.params.id);

	onMount(() => loadUser());

	async function loadUser() {
		loading = true;
		try {
			const res = await api.users.get(userId);
			userProfile = res.data;
			editRole = res.data.role;
		} catch (e: any) {
			error = e.message;
		} finally {
			loading = false;
		}
	}

	async function saveRole() {
		if (!userProfile || editRole === userProfile.role) return;
		saving = true;
		try {
			const res = await api.patch<UserProfile>(`/api/v1/users/${userId}`, { role: editRole });
			userProfile = res.data;
		} catch (e: any) {
			error = e.message;
		} finally {
			saving = false;
		}
	}
</script>

<svelte:head>
	<title>{userProfile?.displayName ?? 'User'} - Administration - Kosha</title>
</svelte:head>

{#if loading}
	<p aria-live="polite" class="text-muted-foreground">Loading user...</p>
{:else if error && !userProfile}
	<ErrorBoundary {error} onRetry={loadUser} />
{:else if userProfile}
	<PageHeader title={userProfile.displayName} description={userProfile.email}>
		<a href="/admin/users" class="rounded-md border border-border px-4 py-2 text-sm font-medium hover:bg-muted focus:outline-2 focus:outline-ring">
			← All Users
		</a>
	</PageHeader>

	<div class="mt-6 grid gap-6 lg:grid-cols-3">
		<div class="lg:col-span-2 space-y-4">
			<section class="rounded-lg border border-border bg-card p-5">
				<h2 class="text-sm font-semibold text-muted-foreground">Role Assignment</h2>
				<div class="mt-3 flex items-end gap-3">
					<div class="flex-1">
						<label for="user-role" class="block text-sm font-medium">Role</label>
						<select id="user-role" bind:value={editRole}
							class="mt-1 w-full rounded-md border border-border bg-background px-3 py-2 text-sm focus:outline-2 focus:outline-ring">
							<option value="CONTRIBUTOR">Contributor</option>
							<option value="EDITOR">Editor</option>
							<option value="DEPT_ADMIN">Department Admin</option>
							<option value="GLOBAL_ADMIN">Global Admin</option>
						</select>
					</div>
					<button onclick={saveRole} disabled={saving || editRole === userProfile.role}
						class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-ring disabled:opacity-50">
						{saving ? 'Saving...' : 'Save'}
					</button>
				</div>
			</section>
		</div>

		<div class="space-y-4">
			<section class="rounded-lg border border-border bg-card p-5">
				<h2 class="mb-3 text-sm font-semibold text-muted-foreground">Profile</h2>
				<dl class="space-y-2 text-sm">
					<div><dt class="text-muted-foreground">Department</dt><dd class="font-medium">{userProfile.departmentName}</dd></div>
					<div><dt class="text-muted-foreground">Role</dt><dd><span class="rounded-md bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary">{userProfile.role}</span></dd></div>
					<div><dt class="text-muted-foreground">Status</dt><dd><StatusBadge status={userProfile.status} /></dd></div>
					<div><dt class="text-muted-foreground">Joined</dt><dd class="font-medium">{new Date(userProfile.createdAt).toLocaleDateString()}</dd></div>
				</dl>
			</section>
		</div>
	</div>
{/if}
