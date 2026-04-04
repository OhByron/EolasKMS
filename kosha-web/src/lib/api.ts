import { get } from 'svelte/store';
import { user } from './auth';
import type {
	ApiResponse,
	DocumentListItem,
	DocumentDetail,
	VersionDetail,
	Department,
	UserProfile,
	AuditEvent,
	ReviewTask,
	TaxonomyTerm,
	TaxonomyTreeNode,
	SearchResult,
	SearchRequest,
	DocumentClassification,
	RetentionPolicy,
	RetentionReview,
	AiConfig,
	AiStats
} from './types/api';

const API_BASE = import.meta.env.VITE_API_URL ?? '';

async function ensureFreshToken(): Promise<string | null> {
	const { userManager } = await import('./auth');
	if (!userManager) return get(user)?.accessToken ?? null;

	try {
		const oidcUser = await userManager.getUser();
		if (oidcUser && !oidcUser.expired) {
			return oidcUser.access_token;
		}
		// Token expired — try silent renew
		const renewed = await userManager.signinSilent();
		if (renewed) {
			// Update the store
			const { initAuth } = await import('./auth');
			await initAuth();
			return renewed.access_token;
		}
	} catch {
		// Silent renew failed — user needs to re-login
	}
	return get(user)?.accessToken ?? null;
}

async function request<T>(path: string, options: RequestInit = {}): Promise<ApiResponse<T>> {
	const token = await ensureFreshToken();
	const headers: Record<string, string> = {
		'Content-Type': 'application/json',
		...(options.headers as Record<string, string>)
	};

	if (token) {
		headers['Authorization'] = `Bearer ${token}`;
	}

	const res = await fetch(`${API_BASE}${path}`, { ...options, headers });

	if (!res.ok) {
		// On 401, try to re-login
		if (res.status === 401) {
			const { login } = await import('./auth');
			await login();
			throw new Error('Session expired. Redirecting to login...');
		}
		const error = await res.json().catch(() => ({ detail: res.statusText }));
		throw new Error(error.detail ?? `HTTP ${res.status}`);
	}

	if (res.status === 204) return { data: null as T };
	return res.json();
}

export const api = {
	get: <T>(path: string) => request<T>(path),
	post: <T>(path: string, body: unknown) =>
		request<T>(path, { method: 'POST', body: JSON.stringify(body) }),
	patch: <T>(path: string, body: unknown) =>
		request<T>(path, { method: 'PATCH', body: JSON.stringify(body) }),
	delete: (path: string) => request(path, { method: 'DELETE' }),

	// --- Documents ---

	documents: {
		list: (page = 0, size = 20) =>
			request<DocumentListItem[]>(`/api/v1/documents?page=${page}&size=${size}`),

		get: (id: string) => request<DocumentDetail>(`/api/v1/documents/${id}`),

		create: (body: { title: string; description?: string; departmentId: string; categoryId?: string; storageMode?: string; workflowType?: string }) =>
			request<DocumentDetail>('/api/v1/documents', { method: 'POST', body: JSON.stringify(body) }),

		update: (id: string, body: Record<string, unknown>) =>
			request<DocumentDetail>(`/api/v1/documents/${id}`, { method: 'PATCH', body: JSON.stringify(body) }),

		delete: (id: string) => request(`/api/v1/documents/${id}`, { method: 'DELETE' }),

		checkout: (id: string) =>
			request<DocumentDetail>(`/api/v1/documents/${id}/checkout`, { method: 'POST' }),

		checkin: (id: string) =>
			request<DocumentDetail>(`/api/v1/documents/${id}/checkin`, { method: 'POST' }),

		versions: (id: string) =>
			request<VersionDetail[]>(`/api/v1/documents/${id}/versions`),

		createVersion: (id: string, body: { fileName: string; fileSizeBytes?: number; storageKey?: string; changeSummary?: string }) =>
			request<VersionDetail>(`/api/v1/documents/${id}/versions`, { method: 'POST', body: JSON.stringify(body) }),
	},

	// --- Departments ---

	departments: {
		list: (page = 0, size = 20) =>
			request<Department[]>(`/api/v1/departments?page=${page}&size=${size}`),

		get: (id: string) => request<Department>(`/api/v1/departments/${id}`),

		create: (body: { name: string; description?: string; parentDeptId?: string }) =>
			request<Department>('/api/v1/departments', { method: 'POST', body: JSON.stringify(body) }),

		update: (id: string, body: Record<string, unknown>) =>
			request<Department>(`/api/v1/departments/${id}`, { method: 'PATCH', body: JSON.stringify(body) }),

		users: (id: string, page = 0, size = 20) =>
			request<UserProfile[]>(`/api/v1/departments/${id}/users?page=${page}&size=${size}`),
	},

	// --- Users ---

	users: {
		list: (page = 0, size = 20) =>
			request<UserProfile[]>(`/api/v1/users?page=${page}&size=${size}`),

		get: (id: string) => request<UserProfile>(`/api/v1/users/${id}`),
	},

	// --- Workflow ---

	workflows: {
		myTasks: (page = 0, size = 20) =>
			request<ReviewTask[]>(`/api/v1/my/workflow-tasks?page=${page}&size=${size}`),

		approve: (workflowId: string, stepId: string, comments?: string) =>
			request(`/api/v1/workflows/${workflowId}/steps/${stepId}/approve`, {
				method: 'POST',
				body: JSON.stringify({ comments })
			}),

		reject: (workflowId: string, stepId: string, comments?: string) =>
			request(`/api/v1/workflows/${workflowId}/steps/${stepId}/reject`, {
				method: 'POST',
				body: JSON.stringify({ comments })
			}),
	},

	// --- Taxonomy ---

	taxonomy: {
		list: () => request<TaxonomyTerm[]>('/api/v1/taxonomy/terms'),

		tree: () => request<TaxonomyTreeNode[]>('/api/v1/taxonomy/terms?format=tree'),

		get: (id: string) => request<TaxonomyTerm>(`/api/v1/taxonomy/terms/${id}`),

		documents: (termId: string, page = 0, size = 20) =>
			request<DocumentListItem[]>(`/api/v1/taxonomy/terms/${termId}/documents?page=${page}&size=${size}`),

		classifications: (documentId: string) =>
			request<DocumentClassification[]>(`/api/v1/documents/${documentId}/classifications`),
	},

	// --- Search ---

	search: {
		query: (req: SearchRequest) =>
			request<SearchResult[]>('/api/v1/search', {
				method: 'POST',
				body: JSON.stringify(req)
			}),

		suggest: (q: string) =>
			request<string[]>(`/api/v1/search/suggest?q=${encodeURIComponent(q)}`),
	},

	// --- Retention ---

	retention: {
		list: (page = 0, size = 20) =>
			request<RetentionPolicy[]>(`/api/v1/retention-policies?page=${page}&size=${size}`),

		get: (id: string) => request<RetentionPolicy>(`/api/v1/retention-policies/${id}`),

		create: (body: { name: string; description?: string; retentionPeriod: string; reviewInterval?: string; actionOnExpiry: string; departmentId?: string }) =>
			request<RetentionPolicy>('/api/v1/retention-policies', { method: 'POST', body: JSON.stringify(body) }),

		update: (id: string, body: Record<string, unknown>) =>
			request<RetentionPolicy>(`/api/v1/retention-policies/${id}`, { method: 'PATCH', body: JSON.stringify(body) }),

		reviews: (page = 0, size = 20) =>
			request<RetentionReview[]>(`/api/v1/retention-reviews?page=${page}&size=${size}`),

		overdueReviews: () =>
			request<RetentionReview[]>('/api/v1/retention-reviews/overdue'),
	},

	// --- AI Admin ---

	ai: {
		getConfig: () => request<AiConfig>('/api/v1/admin/ai/config'),

		updateConfig: (body: Partial<AiConfig>) =>
			request<AiConfig>('/api/v1/admin/ai/config', { method: 'PUT', body: JSON.stringify(body) }),

		getStats: () => request<AiStats>('/api/v1/admin/ai/stats'),

		reprocess: (documentId: string) =>
			request(`/api/v1/admin/ai/reprocess/${documentId}`, { method: 'POST' }),
	},

	// --- Audit ---

	audit: {
		events: (page = 0, size = 50) =>
			request<AuditEvent[]>(`/api/v1/audit/events?page=${page}&size=${size}`),

		byAggregate: (type: string, id: string) =>
			request<AuditEvent[]>(`/api/v1/audit/events/by-aggregate/${type}/${id}`),
	},
};
