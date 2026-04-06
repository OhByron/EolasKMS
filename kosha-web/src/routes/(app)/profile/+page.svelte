<script lang="ts">
	import { user } from '$lib/auth';
	import PageHeader from '$lib/components/kosha/PageHeader.svelte';
	import * as m from '$paraglide/messages';
</script>

<svelte:head><title>{m.page_title_profile()} - {m.nav_app_title()}</title></svelte:head>

<PageHeader title={m.page_title_profile()} />

{#if $user}
	<div class="mt-6 max-w-md space-y-4">
		<div>
			<p class="text-sm font-medium text-muted-foreground">{m.label_name()}</p>
			<p class="text-base">{$user.name}</p>
		</div>
		<div>
			<p class="text-sm font-medium text-muted-foreground">{m.label_email()}</p>
			<p class="text-base">{$user.email}</p>
		</div>
		<div>
			<p class="text-sm font-medium text-muted-foreground">{m.label_roles()}</p>
			<div class="flex gap-2">
				{#each $user.roles as role}
					<span class="rounded-md bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary">{role}</span>
				{/each}
			</div>
		</div>
	</div>
{/if}
