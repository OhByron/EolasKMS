import { get } from 'svelte/store';
import { user } from './auth';
import type {
	ApiResponse,
	DocumentListItem,
	DocumentDetail,
	VersionDetail,
	Department,
	UserProfile,
	ProvisionUserRequest,
	ProvisionedUserResponse,
	PasswordResetResponse,
	WorkflowDefinition,
	UpdateWorkflowRequest,
	WorkflowValidationResponse,
	DocumentCategory,
	LegalReviewSettings,
	WorkflowEscalationSettings,
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
	AiStats,
	AgingReportRow,
	AgingReportSummary,
	CriticalItemRow,
	CriticalItemsSummary,
	LegalHoldRow,
	LegalHoldSummary,
	MailGatewayConfig,
	UpdateMailGatewayRequest,
	TestGatewayRequest,
	GatewayTestResult,
	ProviderPreset,
	NotificationSettings,
	UpdateNotificationSettingsRequest,
	DepartmentScanSettings,
	UpdateDepartmentScanSettingsRequest
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

async function request<T>(path: string, options: RequestInit & { skipAuth?: boolean } = {}): Promise<ApiResponse<T>> {
	const { skipAuth, ...fetchOptions } = options;
	const token = skipAuth ? null : await ensureFreshToken();
	const headers: Record<string, string> = {
		'Content-Type': 'application/json',
		...(fetchOptions.headers as Record<string, string>)
	};

	if (token) {
		headers['Authorization'] = `Bearer ${token}`;
	}

	const res = await fetch(`${API_BASE}${path}`, { ...fetchOptions, headers });

	if (!res.ok) {
		// On 401, try to re-login
		if (res.status === 401 && !skipAuth) {
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
		list: (page = 0, size = 20, departmentId?: string) => {
			const deptSuffix = departmentId ? `&departmentId=${departmentId}` : '';
			return request<DocumentListItem[]>(`/api/v1/documents?page=${page}&size=${size}${deptSuffix}`);
		},

		get: (id: string) => request<DocumentDetail>(`/api/v1/documents/${id}`),

		create: (body: {
			title: string;
			description?: string;
			departmentId: string;
			categoryId?: string;
			storageMode?: string;
			workflowType?: string;
			ownerId?: string;
			requiresLegalReview?: boolean;
			legalReviewerId?: string | null;
		}) =>
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

	// --- Current user ---

	me: {
		get: () => request<UserProfile>('/api/v1/me'),

		/**
		 * Departments the current user is allowed to file documents into.
		 * The upload form uses this in place of departments.list so non-admin
		 * contributors only see their home department. GLOBAL_ADMIN sees all
		 * active departments. The server enforces the same rule on POST, so
		 * this list is advisory — it just narrows the UI.
		 */
		uploadableDepartments: () =>
			request<Department[]>('/api/v1/me/uploadable-departments'),
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

		provisionUser: (id: string, body: ProvisionUserRequest) =>
			request<ProvisionedUserResponse>(`/api/v1/departments/${id}/users`, {
				method: 'POST',
				body: JSON.stringify(body),
			}),

		// Workflow definition (Pass 1 of workflow engine)
		getWorkflow: (id: string) =>
			request<WorkflowDefinition>(`/api/v1/departments/${id}/workflow`),

		updateWorkflow: (id: string, body: UpdateWorkflowRequest) =>
			request<WorkflowDefinition>(`/api/v1/departments/${id}/workflow`, {
				method: 'PUT',
				body: JSON.stringify(body),
			}),

		validateWorkflow: (id: string) =>
			request<WorkflowValidationResponse>(
				`/api/v1/departments/${id}/workflow/validation`
			),
	},

	// --- Users ---

	users: {
		list: (page = 0, size = 20, departmentId?: string) => {
			const deptSuffix = departmentId ? `&departmentId=${departmentId}` : '';
			return request<UserProfile[]>(`/api/v1/users?page=${page}&size=${size}${deptSuffix}`);
		},

		get: (id: string) => request<UserProfile>(`/api/v1/users/${id}`),

		provision: (body: ProvisionUserRequest) =>
			request<ProvisionedUserResponse>('/api/v1/users/provision', {
				method: 'POST',
				body: JSON.stringify(body),
			}),

		update: (id: string, body: { role?: string; status?: string; departmentId?: string; displayName?: string; email?: string }) =>
			request<UserProfile>(`/api/v1/users/${id}`, {
				method: 'PATCH',
				body: JSON.stringify(body),
			}),

		resetPassword: (id: string) =>
			request<PasswordResetResponse>(`/api/v1/users/${id}/reset-password`, {
				method: 'POST',
			}),
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

	// --- Reports ---

	reports: {
		aging: (page = 0, size = 50, departmentId?: string, status?: string) => {
			const params = new URLSearchParams({ page: String(page), size: String(size) });
			if (departmentId) params.set('departmentId', departmentId);
			if (status) params.set('status', status);
			return request<AgingReportRow[]>(`/api/v1/reports/aging?${params}`);
		},

		agingSummary: (departmentId?: string) => {
			const params = departmentId ? `?departmentId=${departmentId}` : '';
			return request<AgingReportSummary>(`/api/v1/reports/aging/summary${params}`);
		},

		criticalItems: (page = 0, size = 50, departmentId?: string, minDaysOverdue?: number) => {
			const params = new URLSearchParams({ page: String(page), size: String(size) });
			if (departmentId) params.set('departmentId', departmentId);
			if (minDaysOverdue !== undefined) params.set('minDaysOverdue', String(minDaysOverdue));
			return request<CriticalItemRow[]>(`/api/v1/reports/critical-items?${params}`);
		},

		criticalItemsSummary: (departmentId?: string) => {
			const params = departmentId ? `?departmentId=${departmentId}` : '';
			return request<CriticalItemsSummary>(`/api/v1/reports/critical-items/summary${params}`);
		},

		notifyCriticalSelected: (reviewIds: string[]) =>
			request<{ notified: number }>('/api/v1/reports/critical-items/notify', {
				method: 'POST', body: JSON.stringify({ reviewIds }),
			}),

		notifyCriticalAll: (departmentId?: string) => {
			const params = departmentId ? `?departmentId=${departmentId}` : '';
			return request<{ notified: number }>(`/api/v1/reports/critical-items/notify-all${params}`, {
				method: 'POST',
			});
		},

		legalHolds: (page = 0, size = 50, departmentId?: string) => {
			const params = new URLSearchParams({ page: String(page), size: String(size) });
			if (departmentId) params.set('departmentId', departmentId);
			return request<LegalHoldRow[]>(`/api/v1/reports/legal-holds?${params}`);
		},

		legalHoldSummary: (departmentId?: string) => {
			const params = departmentId ? `?departmentId=${departmentId}` : '';
			return request<LegalHoldSummary>(`/api/v1/reports/legal-holds/summary${params}`);
		},
	},

	// --- Mail Gateway ---

	mailGateway: {
		get: () => request<MailGatewayConfig>('/api/v1/admin/mail-gateway'),

		update: (body: UpdateMailGatewayRequest) =>
			request<MailGatewayConfig>('/api/v1/admin/mail-gateway', {
				method: 'PUT',
				body: JSON.stringify(body),
			}),

		testConnection: (body: TestGatewayRequest) =>
			request<GatewayTestResult>('/api/v1/admin/mail-gateway/test-connection', {
				method: 'POST',
				body: JSON.stringify(body),
			}),

		testSend: (body: TestGatewayRequest) =>
			request<GatewayTestResult>('/api/v1/admin/mail-gateway/test-send', {
				method: 'POST',
				body: JSON.stringify(body),
			}),

		presets: () =>
			request<ProviderPreset[]>('/api/v1/admin/mail-gateway/presets'),
	},

	// --- Notification Settings (scan intervals) ---

	notificationSettings: {
		getGlobal: () =>
			request<NotificationSettings>('/api/v1/admin/notification-settings'),

		updateGlobal: (body: UpdateNotificationSettingsRequest) =>
			request<NotificationSettings>('/api/v1/admin/notification-settings', {
				method: 'PUT',
				body: JSON.stringify(body),
			}),

		getDepartmentScan: (departmentId: string) =>
			request<DepartmentScanSettings>(`/api/v1/departments/${departmentId}/scan-settings`),

		updateDepartmentScan: (departmentId: string, body: UpdateDepartmentScanSettingsRequest) =>
			request<DepartmentScanSettings>(`/api/v1/departments/${departmentId}/scan-settings`, {
				method: 'PUT',
				body: JSON.stringify(body),
			}),
	},

	// --- Document Categories ---

	documentCategories: {
		list: () =>
			request<DocumentCategory[]>('/api/v1/document-categories'),

		update: (id: string, body: Partial<{ name: string; description: string | null; status: string; suggestsLegalReview: boolean }>) =>
			request<DocumentCategory>(`/api/v1/document-categories/${id}`, {
				method: 'PATCH',
				body: JSON.stringify(body),
			}),
	},

	// --- Legal Review ---

	legalReview: {
		listReviewers: () =>
			request<UserProfile[]>('/api/v1/legal-reviewers'),

		getSettings: () =>
			request<LegalReviewSettings>('/api/v1/admin/legal-review-settings'),

		updateSettings: (body: { defaultTimeLimitDays: number }) =>
			request<LegalReviewSettings>('/api/v1/admin/legal-review-settings', {
				method: 'PUT',
				body: JSON.stringify(body),
			}),
	},

	// --- Workflow Escalation Settings ---

	workflowEscalation: {
		getSettings: () =>
			request<WorkflowEscalationSettings>('/api/v1/admin/workflow-escalation-settings'),

		updateSettings: (body: { scanIntervalMinutes: number }) =>
			request<WorkflowEscalationSettings>('/api/v1/admin/workflow-escalation-settings', {
				method: 'PUT',
				body: JSON.stringify(body),
			}),
	},

	// --- Audit ---

	audit: {
		events: (page = 0, size = 50) =>
			request<AuditEvent[]>(`/api/v1/audit/events?page=${page}&size=${size}`),

		byAggregate: (type: string, id: string) =>
			request<AuditEvent[]>(`/api/v1/audit/events/by-aggregate/${type}/${id}`),
	},
};
