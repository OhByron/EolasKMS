<script lang="ts">
	import { onMount } from 'svelte';
	import { api } from '$lib/api';
	import type { UserProfile } from '$lib/types/api';
	import PageHeader from '$lib/components/kosha/PageHeader.svelte';
	import StatusBadge from '$lib/components/kosha/StatusBadge.svelte';
	import ErrorBoundary from '$lib/components/kosha/ErrorBoundary.svelte';

	let users = $state<UserProfile[]>([]);
	let total = $state(0);
	let loading = $state(true);
	let error = $state('');
	let currentPage = $state(0);
	const pageSize = 25;

	onMount(() => loadUsers());

	async function loadUsers() {
		loading = true;
		error = '';
		try {
			const res = await api.users.list(currentPage, pageSize);
			users = res.data;
			total = res.meta?.total ?? 0;
		} catch (e: any) {
			error = e.message;
		} finally {
			loading = false;
		}
	}
</script>

<svelte:head>
	<title>Users - Administration - Kosha</title>
</svelte:head>

<PageHeader title="All Users" description="{total} user{total !== 1 ? 's' : ''} across all departments" />

{#if loading}
	<p aria-live="polite" class="mt-6 text-muted-foreground">Loading users...</p>
{:else if error}
	<div class="mt-6"><ErrorBoundary {error} onRetry={loadUsers} /></div>
{:else}
	<div class="mt-4 overflow-x-auto rounded-lg border border-border">
		<table class="w-full text-sm" aria-label="All users">
			<thead>
				<tr class="border-b border-border bg-muted/50">
					<th scope="col" class="px-4 py-3 text-left font-semibold">Name</th>
					<th scope="col" class="px-4 py-3 text-left font-semibold">Email</th>
					<th scope="col" class="px-4 py-3 text-left font-semibold">Department</th>
					<th scope="col" class="px-4 py-3 text-left font-semibold">Role</th>
					<th scope="col" class="px-4 py-3 text-left font-semibold">Status</th>
				</tr>
			</thead>
			<tbody>
				{#each users as u}
					<tr class="border-b border-border transition hover:bg-muted/30">
						<td class="px-4 py-3">
							<a href="/admin/users/{u.id}" class="font-medium text-primary hover:underline focus:outline-2 focus:outline-ring">
								{u.displayName}
							</a>
						</td>
						<td class="px-4 py-3 text-muted-foreground">{u.email}</td>
						<td class="px-4 py-3 text-muted-foreground">{u.departmentName}</td>
						<td class="px-4 py-3">
							<span class="rounded-md bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary">{u.role}</span>
						</td>
						<td class="px-4 py-3"><StatusBadge status={u.status} /></td>
					</tr>
				{:else}
					<tr><td colspan="5" class="px-4 py-8 text-center text-muted-foreground">No users found.</td></tr>
				{/each}
			</tbody>
		</table>
	</div>

	{#if total > pageSize}
		<nav aria-label="User list pagination" class="mt-4 flex items-center justify-between text-sm">
			<p class="text-muted-foreground">Page {currentPage + 1} of {Math.ceil(total / pageSize)}</p>
			<div class="flex gap-2">
				<button onclick={() => { currentPage--; loadUsers(); }} disabled={currentPage === 0}
					class="rounded-md border border-border px-3 py-1 hover:bg-muted focus:outline-2 focus:outline-ring disabled:opacity-50 disabled:cursor-not-allowed">
					Previous
				</button>
				<button onclick={() => { currentPage++; loadUsers(); }} disabled={(currentPage + 1) * pageSize >= total}
					class="rounded-md border border-border px-3 py-1 hover:bg-muted focus:outline-2 focus:outline-ring disabled:opacity-50 disabled:cursor-not-allowed">
					Next
				</button>
			</div>
		</nav>
	{/if}
{/if}
