<script lang="ts">
	import { onMount } from 'svelte';
	import { api } from '$lib/api';
	import type { UserProfile, Department } from '$lib/types/api';
	import PageHeader from '$lib/components/kosha/PageHeader.svelte';
	import StatusBadge from '$lib/components/kosha/StatusBadge.svelte';
	import ErrorBoundary from '$lib/components/kosha/ErrorBoundary.svelte';
	import UserCreateModal from '$lib/components/kosha/UserCreateModal.svelte';
	import * as m from '$paraglide/messages';

	let users = $state<UserProfile[]>([]);
	let total = $state(0);
	let loading = $state(true);
	let error = $state('');
	let currentPage = $state(0);
	const pageSize = 25;
	let createModalOpen = $state(false);

	// Department filter state. Empty string means "all departments" — we keep
	// it as a string so it binds cleanly to the <select> element, and convert
	// to undefined when calling the API.
	let departments = $state<Department[]>([]);
	let departmentFilter = $state('');

	onMount(async () => {
		// Load departments once for the filter dropdown; failure here shouldn't
		// block the user list from rendering.
		try {
			const res = await api.departments.list(0, 200);
			departments = res.data;
		} catch {
			// Swallow — the filter just won't be populated.
		}
		await loadUsers();
	});

	async function loadUsers() {
		loading = true;
		error = '';
		try {
			const res = await api.users.list(
				currentPage,
				pageSize,
				departmentFilter || undefined,
			);
			users = res.data;
			total = res.meta?.total ?? 0;
		} catch (e: any) {
			error = e.message;
		} finally {
			loading = false;
		}
	}

	const filteredDeptName = $derived(
		departmentFilter
			? (departments.find((d) => d.id === departmentFilter)?.name ?? 'department')
			: 'all departments',
	);

	function onDepartmentChange() {
		// Reset to first page whenever the filter changes, otherwise we can end
		// up on an empty page past the new total.
		currentPage = 0;
		loadUsers();
	}
</script>

<svelte:head>
	<title>{m.user_all_title()} - {m.nav_sidebar_administration()} - {m.nav_app_title()}</title>
</svelte:head>

<PageHeader title={m.user_all_title()} description={m.user_list_desc({ count: total.toString(), dept: filteredDeptName })}>
	<button
		type="button"
		onclick={() => (createModalOpen = true)}
		class="rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:opacity-90 focus:outline-2 focus:outline-offset-2 focus:outline-ring"
	>
		{m.btn_add_user()}
	</button>
</PageHeader>

<UserCreateModal
	bind:open={createModalOpen}
	onCreated={() => loadUsers()}
/>

<div class="mt-4 flex flex-wrap items-end gap-3">
	<div class="flex flex-col gap-1">
		<label for="dept-filter" class="text-xs font-medium text-muted-foreground">
			{m.label_filter_by_department()}
		</label>
		<select
			id="dept-filter"
			bind:value={departmentFilter}
			onchange={onDepartmentChange}
			class="rounded-md border border-border bg-background px-3 py-1.5 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
		>
			<option value="">{m.label_all_departments()}</option>
			{#each departments as d (d.id)}
				<option value={d.id}>{d.name}</option>
			{/each}
		</select>
	</div>
	{#if departmentFilter}
		<button
			type="button"
			onclick={() => { departmentFilter = ''; onDepartmentChange(); }}
			class="rounded-md border border-border px-3 py-1.5 text-xs font-medium hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring"
		>
			{m.btn_clear_filter()}
		</button>
	{/if}
</div>

{#if loading}
	<p aria-live="polite" class="mt-6 text-muted-foreground">{m.user_loading()}</p>
{:else if error}
	<div class="mt-6"><ErrorBoundary {error} onRetry={loadUsers} /></div>
{:else}
	<div class="mt-4 overflow-x-auto rounded-lg border border-border">
		<table class="w-full text-sm" aria-label="All users">
			<thead>
				<tr class="border-b border-border bg-muted/50">
					<th scope="col" class="px-4 py-3 text-left font-semibold">{m.label_name()}</th>
					<th scope="col" class="px-4 py-3 text-left font-semibold">{m.label_email()}</th>
					<th scope="col" class="px-4 py-3 text-left font-semibold">{m.label_department()}</th>
					<th scope="col" class="px-4 py-3 text-left font-semibold">{m.label_role()}</th>
					<th scope="col" class="px-4 py-3 text-left font-semibold">{m.label_status()}</th>
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
					<tr><td colspan="5" class="px-4 py-8 text-center text-muted-foreground">{m.user_no_users()}</td></tr>
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
					{m.btn_previous()}
				</button>
				<button onclick={() => { currentPage++; loadUsers(); }} disabled={(currentPage + 1) * pageSize >= total}
					class="rounded-md border border-border px-3 py-1 hover:bg-muted focus:outline-2 focus:outline-ring disabled:opacity-50 disabled:cursor-not-allowed">
					{m.btn_next()}
				</button>
			</div>
		</nav>
	{/if}
{/if}
