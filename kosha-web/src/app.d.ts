// See https://svelte.dev/docs/kit/types#app.d.ts
// for information about these interfaces
declare global {
	namespace App {
		// interface Error {}
		interface Locals {
			/**
			 * Active locale for the current request, set in hooks.server.ts
			 * from the cookie/Accept-Language and consumed by the paraglide
			 * runtime so SSR-rendered m.* calls match the request's language.
			 */
			lang: string;
		}
		// interface PageData {}
		// interface PageState {}
		// interface Platform {}
	}
}

export {};
