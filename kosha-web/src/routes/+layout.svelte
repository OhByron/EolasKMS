<script lang="ts">
	import '../app.css';
	import { page } from '$app/state';
	import { setLocale, locales } from '$paraglide/runtime';

	let { children } = $props();

	// Client-side language sync. On SPA navigations (after initial SSR),
	// the server hook doesn't run, so we need to read the lang from the
	// URL and set it on the Paraglide runtime ourselves. This $effect
	// fires on every navigation and keeps the runtime in sync.
	const supported = new Set<string>(locales as readonly string[]);

	$effect(() => {
		const paramLang = page.url?.searchParams.get('lang')?.toLowerCase();
		if (paramLang && supported.has(paramLang)) {
			setLocale(paramLang as typeof locales[number], { reload: false });
		}
	});
</script>

{@render children()}
