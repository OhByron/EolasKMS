<script lang="ts">
	import { user, logout } from '$lib/auth';
	import * as m from '$paraglide/messages';
	import { languageTag } from '$paraglide/runtime';

	let searchQuery = $state('');
	let userMenuOpen = $state(false);

	/**
	 * Human-readable labels for each supported language. Shown in the
	 * language picker dropdown in their own script so a German user
	 * sees "Deutsch", not "German".
	 */
	const languageLabels: Record<string, string> = {
		en: 'English',
		de: 'Deutsch',
		fr: 'Français',
		es: 'Español',
		it: 'Italiano',
		pt: 'Português',
		nl: 'Nederlands',
		pl: 'Polski',
		da: 'Dansk',
		nb: 'Norsk bokmål',
		is: 'Íslenska',
		sv: 'Svenska',
		ar: 'العربية',
		'zh-Hans': '简体中文',
		ja: '日本語',
		ko: '한국어',
	};

	const currentLang = $derived(languageTag());

	function switchLanguage(lang: string) {
		// Persist the choice in a cookie so the server hook picks it up
		// on subsequent requests without a query param.
		document.cookie = `eolas_lang=${lang};path=/;max-age=${60 * 60 * 24 * 365};SameSite=Lax`;

		// Navigate to the same page with the lang param — the root
		// layout's $effect and the server hook both read it.
		const url = new URL(window.location.href);
		url.searchParams.set('lang', lang);
		window.location.href = url.toString();
	}

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
		<img src="/favicon.png" alt="" class="h-8 w-8" aria-hidden="true">
		<span>{m.nav_app_title()}</span>
	</a>

	<form onsubmit={handleSearch} class="ml-4 flex-1 max-w-xl" role="search">
		<label for="global-search" class="sr-only">{m.nav_sidebar_search()}</label>
		<input
			id="global-search"
			type="search"
			bind:value={searchQuery}
			placeholder={m.topbar_search_placeholder()}
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
							{m.topbar_profile()}
						</a>

						<!-- Language selector -->
						<div class="border-t border-border px-3 py-2">
							<label for="lang-select" class="block text-xs font-medium text-muted-foreground">
								🌐 Language
							</label>
							<select
								id="lang-select"
								value={currentLang}
								onchange={(e) => switchLanguage((e.target as HTMLSelectElement).value)}
								class="mt-1 w-full rounded-md border border-border bg-background px-2 py-1 text-sm focus:border-ring focus:outline-2 focus:outline-offset-2 focus:outline-ring"
							>
								{#each Object.entries(languageLabels) as [code, label]}
									<option value={code}>{label}</option>
								{/each}
							</select>
						</div>

						<button
							role="menuitem"
							class="w-full border-t border-border px-3 py-2 text-left text-sm hover:bg-muted focus:bg-muted focus:outline-none"
							onclick={() => { userMenuOpen = false; logout(); }}
						>
							{m.topbar_sign_out()}
						</button>
					</div>
				{/if}
			</div>
		{/if}
	</div>
</header>
