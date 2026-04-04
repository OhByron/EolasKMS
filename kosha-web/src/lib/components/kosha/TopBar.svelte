<script lang="ts">
	import { user, logout } from '$lib/auth';

	let searchQuery = $state('');
	let userMenuOpen = $state(false);

	function handleSearch(e: Event) {
		e.preventDefault();
		if (searchQuery.trim()) {
			window.location.href = `/search?q=${encodeURIComponent(searchQuery.trim())}`;
		}
	}

	function handleKeydown(e: KeyboardEvent) {
		if (e.key === 'Escape') {
			userMenuOpen = false;
		}
	}
</script>

<header class="flex h-14 items-center gap-4 border-b border-border bg-card px-4">
	<a href="/dashboard" class="flex items-center gap-2 text-lg font-bold text-primary focus:outline-2 focus:outline-offset-2 focus:outline-ring">
		<img src="/favicon.svg" alt="" class="h-7 w-7 rounded" aria-hidden="true">
		<span>Kosha</span>
		<span class="text-accent text-base font-semibold">कोश</span>
	</a>

	<form onsubmit={handleSearch} class="ml-4 flex-1 max-w-xl" role="search">
		<label for="global-search" class="sr-only">Search documents</label>
		<input
			id="global-search"
			type="search"
			bind:value={searchQuery}
			placeholder="Search documents, keywords..."
			class="w-full rounded-md border border-border bg-background px-3 py-1.5 text-sm placeholder:text-muted-foreground focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
		/>
	</form>

	<div class="ml-auto flex items-center gap-2">
		{#if $user}
			<!-- svelte-ignore a11y_no_static_element_interactions -->
			<div class="relative" onkeydown={handleKeydown}>
				<button
					onclick={() => (userMenuOpen = !userMenuOpen)}
					class="flex items-center gap-2 rounded-md px-2 py-1 text-sm hover:bg-muted focus:outline-2 focus:outline-offset-2 focus:outline-ring"
					aria-expanded={userMenuOpen}
					aria-haspopup="true"
				>
					<span
						class="flex h-7 w-7 items-center justify-center rounded-full bg-primary text-xs font-bold text-primary-foreground"
						aria-hidden="true"
					>
						{$user.name.charAt(0).toUpperCase()}
					</span>
					<span class="hidden sm:inline">{$user.name}</span>
				</button>

				{#if userMenuOpen}
					<div
						class="absolute right-0 top-full z-50 mt-1 w-48 rounded-md border border-border bg-card py-1 shadow-lg"
						role="menu"
						aria-label="User menu"
					>
						<div class="border-b border-border px-3 py-2">
							<p class="text-sm font-medium">{$user.name}</p>
							<p class="text-xs text-muted-foreground">{$user.email}</p>
						</div>
						<a
							href="/profile"
							role="menuitem"
							class="block px-3 py-2 text-sm hover:bg-muted focus:bg-muted focus:outline-none"
							onclick={() => (userMenuOpen = false)}
						>
							Profile
						</a>
						<button
							role="menuitem"
							class="w-full px-3 py-2 text-left text-sm hover:bg-muted focus:bg-muted focus:outline-none"
							onclick={() => { userMenuOpen = false; logout(); }}
						>
							Sign out
						</button>
					</div>
				{/if}
			</div>
		{/if}
	</div>
</header>
