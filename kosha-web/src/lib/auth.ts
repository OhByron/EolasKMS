import { UserManager, WebStorageStateStore } from 'oidc-client-ts';
import { writable, get } from 'svelte/store';

const KEYCLOAK_URL = import.meta.env.VITE_KEYCLOAK_URL ?? 'http://localhost:8180';
const REALM = 'kosha';
const CLIENT_ID = 'kosha-web';

const settings = {
	authority: `${KEYCLOAK_URL}/realms/${REALM}`,
	client_id: CLIENT_ID,
	redirect_uri: `${typeof window !== 'undefined' ? window.location.origin : ''}/auth/callback`,
	post_logout_redirect_uri: typeof window !== 'undefined' ? window.location.origin : '',
	response_type: 'code',
	scope: 'openid',
	userStore: typeof window !== 'undefined' ? new WebStorageStateStore({ store: window.localStorage }) : undefined,
	automaticSilentRenew: true
};

export const userManager = typeof window !== 'undefined' ? new UserManager(settings) : null;

export interface KoshaUser {
	id: string;          // Keycloak sub
	profileId: string;   // Kosha user_profile.id
	name: string;
	email: string;
	roles: string[];
	accessToken: string;
}

export const user = writable<KoshaUser | null>(null);

export async function initAuth() {
	if (!userManager) return;
	try {
		const oidcUser = await userManager.getUser();
		if (oidcUser && !oidcUser.expired) {
			await setUser(oidcUser);
		}
	} catch {
		// not logged in
	}
}

export async function login() {
	await userManager?.signinRedirect();
}

export async function handleCallback() {
	if (!userManager) return;
	const oidcUser = await userManager.signinRedirectCallback();
	await setUser(oidcUser);
}

export async function logout() {
	await userManager?.signoutRedirect();
	user.set(null);
}

// --- Role helpers ---

export function hasRole(role: string): boolean {
	const u = get(user);
	return u?.roles?.includes(role) ?? false;
}

export function hasAnyRole(...roles: string[]): boolean {
	const u = get(user);
	return roles.some((r) => u?.roles?.includes(r));
}

export function isGlobalAdmin(): boolean {
	return hasRole('GLOBAL_ADMIN');
}

export function isDeptAdminOrAbove(): boolean {
	return hasAnyRole('GLOBAL_ADMIN', 'DEPT_ADMIN');
}

export function isEditorOrAbove(): boolean {
	return hasAnyRole('GLOBAL_ADMIN', 'DEPT_ADMIN', 'EDITOR');
}

async function setUser(oidcUser: any) {
	const accessToken = oidcUser.access_token;
	const name = oidcUser.profile.name ?? oidcUser.profile.preferred_username ?? '';
	const email = oidcUser.profile.email ?? '';
	const roles = oidcUser.profile.roles ?? [];
	const keycloakId = oidcUser.profile.sub ?? '';

	// Fetch the Kosha profile to get the internal user ID
	let profileId = '';
	try {
		const res = await fetch('/api/v1/me', {
			headers: { Authorization: `Bearer ${accessToken}` }
		});
		if (res.ok) {
			const data = await res.json();
			profileId = data.data?.id ?? '';
		}
	} catch {
		// Profile fetch failed — user may not be provisioned yet
	}

	user.set({ id: keycloakId, profileId, name, email, roles, accessToken });
}
