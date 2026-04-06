<script lang="ts">
	import '../app.css';
	import { page } from '$app/state';
	import { setLanguageTag, availableLanguageTags, type AvailableLanguageTag } from '$paraglide/runtime';

	let { children } = $props();

	// Client-side language sync. On SPA navigations (after initial SSR),
	// the server hook doesn't run, so we need to read the lang from the
	// URL and set it on the Paraglide runtime ourselves. This $effect
	// fires on every navigation and keeps the runtime in sync.
	const supported = new Set<string>(availableLanguageTags as readonly string[]);

	$effect(() => {
		const paramLang = page.url?.searchParams.get('lang')?.toLowerCase();
		if (paramLang && supported.has(paramLang)) {
			setLanguageTag(paramLang as AvailableLanguageTag);
		}
	});
</script>

{@render children()}
