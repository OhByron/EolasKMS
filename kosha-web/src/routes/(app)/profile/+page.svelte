<script lang="ts">
	import { onMount } from 'svelte';
	import { user } from '$lib/auth';
	import { api } from '$lib/api';
	import type { UserProfile } from '$lib/types/api';
	import PageHeader from '$lib/components/kosha/PageHeader.svelte';
	import StatusBadge from '$lib/components/kosha/StatusBadge.svelte';
	import * as m from '$paraglide/messages';
	import { languageTag } from '$paraglide/runtime';

	let profile = $state<UserProfile | null>(null);
	let loading = $state(true);

	const currentLang = $derived(languageTag());

	onMount(async () => {
		try {
			const res = await api.me.get();
			profile = res.data;
		} catch {
			// Fall back to auth store data
		} finally {
			loading = false;
		}
	});

	function formatDate(iso: string): string {
		return new Date(iso).toLocaleDateString(currentLang, {
			year: 'numeric', month: 'long', day: 'numeric'
		});
	}
</script>

<svelte:head>
	<title>{m.page_title_profile()} - {m.nav_app_title()}</title>
</svelte:head>

<PageHeader title={m.page_title_profile()} />

{#if loading}
	<p class="mt-6 text-muted-foreground">{m.app_loading()}</p>
{:else}
	<div class="mt-6 grid gap-6 lg:grid-cols-3">
		<!-- Main profile card -->
		<div class="space-y-6 lg:col-span-2">
			<section class="rounded-lg border border-border bg-card p-6">
				<div class="flex items-center gap-4">
					<div
						class="flex h-16 w-16 items-center justify-center rounded-full bg-primary text-2xl font-bold text-primary-foreground"
						aria-hidden="true"
					>
						{($user?.name ?? '?').charAt(0).toUpperCase()}
					</div>
					<div>
						<h2 class="text-xl font-semibold">{profile?.displayName ?? $user?.name}</h2>
						<p class="text-sm text-muted-foreground">{profile?.email ?? $user?.email}</p>
					</div>
				</div>

				<dl class="mt-6 grid gap-4 sm:grid-cols-2">
					<div>
						<dt class="text-xs font-medium text-muted-foreground">{m.label_department()}</dt>
						<dd class="mt-1 text-sm font-medium">{profile?.departmentName ?? '-'}</dd>
					</div>
					<div>
						<dt class="text-xs font-medium text-muted-foreground">{m.label_role()}</dt>
						<dd class="mt-1">
							<span class="rounded-md bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary">
								{profile?.role ?? $user?.roles?.[0] ?? '-'}
							</span>
						</dd>
					</div>
					<div>
						<dt class="text-xs font-medium text-muted-foreground">{m.label_status()}</dt>
						<dd class="mt-1">
							{#if profile?.status}
								<StatusBadge status={profile.status} />
							{:else}
								<span class="text-sm">-</span>
							{/if}
						</dd>
					</div>
					<div>
						<dt class="text-xs font-medium text-muted-foreground">{m.label_joined()}</dt>
						<dd class="mt-1 text-sm">{profile?.createdAt ? formatDate(profile.createdAt) : '-'}</dd>
					</div>
				</dl>
			</section>

			<!-- Keycloak roles (raw) -->
			{#if $user?.roles && $user.roles.length > 0}
				<section class="rounded-lg border border-border bg-card p-6">
					<h2 class="text-sm font-semibold text-muted-foreground">{m.label_roles()}</h2>
					<div class="mt-3 flex flex-wrap gap-2">
						{#each $user.roles as role}
							<span class="rounded-md border border-border bg-muted px-3 py-1 text-xs font-medium">
								{role}
							</span>
						{/each}
					</div>
					<p class="mt-2 text-xs text-muted-foreground">
						Roles are managed by your system administrator via Keycloak.
					</p>
				</section>
			{/if}
		</div>

		<!-- Sidebar -->
		<div class="space-y-4">
			<section class="rounded-lg border border-border bg-card p-5">
				<h2 class="mb-3 text-sm font-semibold text-muted-foreground">{m.label_details()}</h2>
				<dl class="space-y-3 text-sm">
					<div>
						<dt class="text-muted-foreground">{m.label_email()}</dt>
						<dd class="font-medium break-all">{profile?.email ?? $user?.email}</dd>
					</div>
					<div>
						<dt class="text-muted-foreground">{m.label_department()}</dt>
						<dd class="font-medium">{profile?.departmentName ?? '-'}</dd>
					</div>
					<div>
						<dt class="text-muted-foreground">{m.label_last_update()}</dt>
						<dd class="font-medium">{profile?.updatedAt ? formatDate(profile.updatedAt) : '-'}</dd>
					</div>
				</dl>
			</section>

			<section class="rounded-lg border border-border bg-card p-5">
				<h2 class="mb-3 text-sm font-semibold text-muted-foreground">{m.nav_app_title()}</h2>
				<dl class="space-y-2 text-sm">
					<div>
						<dt class="text-muted-foreground">Version</dt>
						<dd class="font-mono text-xs">1.0.0</dd>
					</div>
					<div>
						<dt class="text-muted-foreground">Flyway</dt>
						<dd class="font-mono text-xs">V033</dd>
					</div>
				</dl>
				<a
					href="/about"
					class="mt-3 block text-xs text-primary underline hover:opacity-80 focus:outline-2 focus:outline-ring"
				>
					About Eòlas
				</a>
			</section>
		</div>
	</div>
{/if}
