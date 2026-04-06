import type { Handle } from '@sveltejs/kit';
import { sequence } from '@sveltejs/kit/hooks';
import { setLanguageTag, availableLanguageTags, type AvailableLanguageTag } from '$paraglide/runtime';

/**
 * i18n middleware — resolves the user's preferred language and sets it
 * on the Paraglide runtime so all `m.key()` calls in server-rendered
 * components return the correct translation.
 *
 * Language resolution priority:
 *   1. `?lang=` query parameter (case-insensitive)
 *   2. `eolas_lang` cookie (set by the language picker)
 *   3. `Accept-Language` header (first supported tag)
 *   4. `en` (fallback)
 */
const i18n: Handle = async ({ event, resolve }) => {
	const supported = new Set<string>(availableLanguageTags as readonly string[]);

	// Resolve from sources, normalising to lowercase
	const paramLang = event.url.searchParams.get('lang')?.toLowerCase();
	const cookieLang = event.cookies.get('eolas_lang')?.toLowerCase();
	const headerLang = event.request.headers.get('accept-language')
		?.split(',')
		.map((s) => s.split(';')[0].trim().toLowerCase())
		.flatMap((tag) => [tag, tag.split('-')[0]]) // try full tag then base
		.find((tag) => supported.has(tag));

	const lang = [paramLang, cookieLang, headerLang].find((l) => l && supported.has(l)) ?? 'en';

	// Set on the Paraglide runtime so m.key() calls during SSR return
	// the right language. This is the critical line — without it,
	// languageTag() returns 'en' forever and no translation renders.
	setLanguageTag(lang as AvailableLanguageTag);

	event.locals.lang = lang;

	return resolve(event, {
		transformPageChunk: ({ html }) => html.replace('%lang%', lang),
	});
};

export const handle = sequence(i18n);
