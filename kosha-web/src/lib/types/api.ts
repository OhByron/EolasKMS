export interface ApiResponse<T> {
	data: T;
	meta?: PageMeta;
	links?: Links;
}

export interface PageMeta {
	page: number;
	size: number;
	total: number;
}

export interface Links {
	self: string;
	next?: string;
	prev?: string;
}

export interface ApiError {
	type: string;
	title: string;
	status: number;
	detail?: string;
	instance?: string;
}

// --- Identity ---

export interface Department {
	id: string;
	name: string;
	description: string | null;
	managerUserId: string | null;
	parentDeptId: string | null;
	status: string;
	/** When true, members of this department can be picked as legal reviewers. */
	handlesLegalReview: boolean;
	createdAt: string;
	updatedAt: string;
}

export interface UserProfile {
	id: string;
	keycloakId: string;
	displayName: string;
	email: string;
	departmentId: string;
	departmentName: string;
	role: string;
	status: string;
	createdAt: string;
	updatedAt: string;
}

// --- Documents ---

export interface DocumentListItem {
	id: string;
	docNumber: string | null;
	title: string;
	departmentName: string;
	status: string;
	checkedOut: boolean;
	currentVersion: string | null;
	primaryOwnerName: string;
	createdAt: string;
}

export interface DocumentDetail {
	id: string;
	docNumber: string | null;
	title: string;
	description: string | null;
	departmentId: string;
	departmentName: string;
	categoryId: string | null;
	categoryName: string | null;
	status: string;
	storageMode: string;
	workflowType: string;
	checkedOut: boolean;
	lockedBy: string | null;
	currentVersion: VersionSummary | null;
	createdBy: string;
	primaryOwnerId: string;
	primaryOwnerName: string;
	proxyOwnerId: string | null;
	proxyOwnerName: string | null;
	requiresLegalReview: boolean;
	legalReviewerId: string | null;
	legalReviewerName: string | null;
	createdAt: string;
	updatedAt: string;
}

// --- Document Categories ---

export interface DocumentCategory {
	id: string;
	name: string;
	description: string | null;
	departmentId: string | null;
	status: string;
	suggestsLegalReview: boolean;
}

// --- Legal Review Settings ---

export interface LegalReviewSettings {
	defaultTimeLimitDays: number;
	updatedAt: string;
}

export interface VersionSummary {
	id: string;
	versionNumber: string;
	fileName: string;
	status: string;
	createdAt: string;
}

export interface VersionDetail {
	id: string;
	documentId: string;
	versionNumber: string;
	fileName: string;
	fileSizeBytes: number | null;
	contentHash: string | null;
	storageKey: string | null;
	contentType: string | null;
	ocrApplied: boolean;
	ocrLanguage: string | null;
	extractedMetadata: Record<string, unknown> | null;
	changeSummary: string | null;
	status: string;
	createdBy: string;
	publishAt: string | null;
	createdAt: string;
	metadata: VersionMetadata | null;
}

export interface VersionMetadata {
	summary: string | null;
	aiConfidence: number | null;
	humanReviewed: boolean;
}

// --- Workflow ---

export interface WorkflowDefinition {
	id: string;
	name: string;
	description: string | null;
	workflowType: string;
	departmentId: string | null;
	isDefault: boolean;
	createdAt: string;
}

export interface WorkflowInstance {
	id: string;
	workflowDefId: string;
	documentId: string;
	versionId: string;
	initiatedBy: string;
	status: string;
	startedAt: string;
	completedAt: string | null;
}

export interface WorkflowStepInstance {
	id: string;
	workflowInstId: string;
	stepDefId: string;
	assignedTo: string | null;
	status: string;
	comments: string | null;
	decidedAt: string | null;
	createdAt: string;
}

export interface ReviewTask {
	documentId: string;
	documentTitle: string;
	documentDepartment: string;
	submittedBy: string;
	submittedByName: string;
	submittedAt: string;
	workflowInstanceId: string;
	stepInstanceId: string;
	stepName: string;
	status: string;
}

// --- Taxonomy ---

export interface TaxonomyTerm {
	id: string;
	label: string;
	normalizedLabel: string;
	description: string | null;
	source: string;
	sourceRef: string | null;
	status: string;
	mergedIntoId: string | null;
	createdAt: string;
	updatedAt: string;
}

export interface TaxonomyEdge {
	id: string;
	parentTermId: string;
	childTermId: string;
	edgeType: string;
	createdAt: string;
}

export interface TaxonomyTreeNode {
	term: TaxonomyTerm;
	children: TaxonomyTreeNode[];
}

export interface DocumentClassification {
	id: string;
	documentId: string;
	termId: string;
	confidence: number | null;
	source: string;
	createdAt: string;
}

// --- Taxonomy Import ---

export interface TaxonomyImportRowPreview {
	row: number;
	label: string;
	description: string;
	parentLabel: string;
	isDuplicate: boolean;
	errors: string[];
	ok: boolean;
}

export interface TaxonomyImportPreview {
	totalRows: number;
	newTerms: number;
	duplicates: number;
	errors: number;
	rows: TaxonomyImportRowPreview[];
	globalErrors: string[];
}

export interface TaxonomyImportResultRow {
	row: number;
	label: string;
	status: string;
	message: string | null;
}

export interface TaxonomyImportResult {
	created: number;
	skipped: number;
	errors: number;
	details: TaxonomyImportResultRow[];
}

// --- Search ---

export interface SearchRequest {
	query: string;
	filters?: {
		departmentId?: string;
		status?: string[];
		taxonomyTerms?: string[];
		dateFrom?: string;
		dateTo?: string;
		mimeType?: string[];
	};
	sort?: string;
	page?: number;
	size?: number;
}

export interface SearchResult {
	id: string;
	title: string;
	docNumber: string | null;
	departmentName: string;
	status: string;
	snippet: string | null;
	relevance: number;
	taxonomyTerms: string[];
	createdAt: string;
}

// --- Retention ---

export interface RetentionPolicy {
	id: string;
	name: string;
	description: string | null;
	retentionPeriod: string;
	reviewInterval: string | null;
	actionOnExpiry: string;
	departmentId: string | null;
	status: string;
	createdAt: string;
	updatedAt: string;
}

export interface RetentionReview {
	id: string;
	documentId: string;
	policyId: string;
	dueAt: string;
	completedAt: string | null;
	reviewedBy: string | null;
	outcome: string | null;
	notes: string | null;
	createdAt: string;
}

// --- Reports ---

export interface AgingReportRow {
	documentId: string;
	docNumber: string;
	title: string;
	departmentId: string;
	departmentName: string;
	status: string;
	categoryName: string | null;
	retentionPolicyName: string | null;
	retentionPeriod: string | null;
	actionOnExpiry: string | null;
	createdAt: string;
	ageDays: number;
	ageBand: string;
	latestVersionNumber: string | null;
	nextReviewAt: string | null;
	hasOverdueReview: boolean;
}

export interface AgingReportSummary {
	totalDocuments: number;
	byAgeBand: AgeBandCount[];
	byDepartment: DepartmentAgingSummary[];
}

export interface AgeBandCount {
	ageBand: string;
	count: number;
}

export interface DepartmentAgingSummary {
	departmentId: string;
	departmentName: string;
	totalDocuments: number;
	avgAgeDays: number;
	oldestDocumentDays: number;
}

export interface CriticalItemRow {
	documentId: string;
	docNumber: string;
	title: string;
	departmentId: string;
	departmentName: string;
	status: string;
	retentionPolicyName: string;
	retentionPeriod: string;
	actionOnExpiry: string;
	createdAt: string;
	ageDays: number;
	reviewDueAt: string;
	daysOverdue: number;
	severity: string;
	reviewId: string;
}

export interface CriticalItemsSummary {
	totalCriticalItems: number;
	bySeverity: SeverityCount[];
	byDepartment: DepartmentCriticalSummary[];
}

export interface SeverityCount {
	severity: string;
	count: number;
}

export interface DepartmentCriticalSummary {
	departmentId: string;
	departmentName: string;
	criticalCount: number;
	oldestOverdueDays: number;
}

export interface LegalHoldRow {
	documentId: string;
	docNumber: string;
	title: string;
	departmentId: string;
	departmentName: string;
	categoryName: string | null;
	createdAt: string;
	holdSinceDays: number;
	latestVersionNumber: string | null;
	createdByName: string | null;
	retentionPolicyName: string | null;
	originalRetentionPeriod: string | null;
}

export interface LegalHoldSummary {
	totalOnHold: number;
	byDepartment: DepartmentHoldSummary[];
}

export interface DepartmentHoldSummary {
	departmentId: string;
	departmentName: string;
	holdCount: number;
	avgHoldDays: number;
}

// --- Mail Gateway ---

export interface MailGatewayConfig {
	provider: string;
	transport: string;
	host: string;
	port: number;
	encryption: 'starttls' | 'tls' | 'none';
	skipTlsVerify: boolean;
	username: string | null;
	hasPassword: boolean;
	fromEmail: string;
	fromName: string;
	replyToEmail: string | null;
	region: string | null;
	sandboxMode: boolean;
	connectionTimeoutMs: number;
	readTimeoutMs: number;
	lastTestedAt: string | null;
	lastTestSuccess: boolean | null;
	lastTestError: string | null;
	updatedAt: string;
}

export interface UpdateMailGatewayRequest {
	provider: string;
	transport?: string;
	host: string;
	port: number;
	encryption: 'starttls' | 'tls' | 'none';
	skipTlsVerify?: boolean;
	username?: string | null;
	password?: string | null;  // null = keep existing, '' = clear, else re-encrypt
	fromEmail: string;
	fromName: string;
	replyToEmail?: string | null;
	region?: string | null;
	sandboxMode?: boolean;
	connectionTimeoutMs?: number;
	readTimeoutMs?: number;
}

export interface TestGatewayRequest {
	provider: string;
	transport?: string;
	host: string;
	port: number;
	encryption: 'starttls' | 'tls' | 'none';
	skipTlsVerify?: boolean;
	username?: string | null;
	password?: string | null;
	fromEmail: string;
	fromName: string;
	testRecipient?: string | null;
	connectionTimeoutMs?: number;
	readTimeoutMs?: number;
}

export interface GatewayTestResult {
	success: boolean;
	message: string;
	detail: string | null;
}

export interface ProviderPreset {
	key: string;
	label: string;
	description: string;
	transport: string;
	defaultHost: string | null;
	defaultPort: number;
	defaultEncryption: 'starttls' | 'tls' | 'none';
	fixedUsername: string | null;
	showUsername: boolean;
	showPassword: boolean;
	showRegion: boolean;
	regions: RegionOption[];
	showSkipTlsVerify: boolean;
	warning: string | null;
	hostHint: string | null;
	devOnly: boolean;
}

export interface RegionOption {
	key: string;
	label: string;
	host: string;
}

// --- Workflow definition ---

export type WorkflowType = 'LINEAR' | 'PARALLEL';
export type ActionType = 'REVIEW' | 'APPROVE' | 'SIGN_OFF';

export interface WorkflowStep {
	id: string;
	stepOrder: number;
	name: string;
	actionType: ActionType;
	assigneeId: string | null;
	assigneeName: string | null;
	assigneeStatus: string | null;
	escalationId: string | null;
	escalationName: string | null;
	escalationStatus: string | null;
	timeLimitDays: number;
	conditionJson: string | null;
}

export interface WorkflowDefinition {
	id: string;
	departmentId: string;
	departmentName: string;
	name: string;
	description: string | null;
	workflowType: WorkflowType;
	isDefault: boolean;
	steps: WorkflowStep[];
	createdAt: string;
	updatedAt: string;
}

export interface UpdateWorkflowStepRequest {
	name: string;
	actionType: ActionType;
	assigneeId: string;
	escalationId: string;
	timeLimitDays: number;
	conditionJson?: string | null;
}

export interface UpdateWorkflowRequest {
	name?: string;
	description?: string | null;
	workflowType: WorkflowType;
	steps: UpdateWorkflowStepRequest[];
}

export interface WorkflowValidationResponse {
	ready: boolean;
	problems: string[];
}

// --- User Provisioning ---

export interface ProvisionUserRequest {
	displayName: string;
	email: string;
	departmentId: string;
	role: 'GLOBAL_ADMIN' | 'DEPT_ADMIN' | 'EDITOR' | 'CONTRIBUTOR';
	temporaryPassword?: string | null;
}

export interface ProvisionedUserResponse {
	user: UserProfile;
	temporaryPassword: string;
	mustChangePasswordOnFirstLogin: boolean;
}

export interface PasswordResetResponse {
	user: UserProfile;
	temporaryPassword: string;
	emailDispatched: boolean;
}

// --- Share links (Pass 5.5) ---

export interface ShareLinkCreated {
	id: string;
	token: string;
	expiresAt: string;
	hasPassword: boolean;
	maxAccess: number | null;
}

export interface ShareLinkSummary {
	id: string;
	versionNumber: string;
	expiresAt: string;
	hasPassword: boolean;
	maxAccess: number | null;
	accessCount: number;
	revoked: boolean;
	createdAt: string;
	createdByName: string;
}

export interface ShareLinkResolved {
	documentId: string;
	documentTitle: string;
	departmentName: string;
	versionId: string;
	versionNumber: string;
	fileName: string;
	contentType: string | null;
	storageKey: string | null;
	ocrApplied: boolean;
	ocrStorageKey: string | null;
}

// --- Signatures (Pass 5.2) ---

export interface DocumentSignature {
	id: string;
	documentId: string;
	versionId: string;
	versionNumber: string;
	signerId: string;
	signerName: string;
	signerEmail: string;
	typedName: string;
	contentHash: string;
	signedAt: string;
}

// --- Bulk import (Pass 4.2.3) ---

export interface ImportDryRunResponse {
	totalRows: number;
	validRows: number;
	invalidRows: number;
	rows: ImportDryRunRow[];
	globalErrors: string[];
}

export interface ImportDryRunRow {
	row: number;
	filePath: string;
	ok: boolean;
	errors: string[];
	resolved?: {
		departmentId: string;
		categoryId: string | null;
		ownerId: string;
		legalReviewerId: string | null;
	} | null;
	autoProvisionOwner?: {
		email: string;
		displayName: string;
		departmentId: string;
	} | null;
}

export interface UserImportDryRunResponse {
	totalRows: number;
	validRows: number;
	invalidRows: number;
	rows: UserImportDryRunRow[];
	globalErrors: string[];
}

export interface UserImportDryRunRow {
	row: number;
	email: string;
	ok: boolean;
	errors: string[];
	resolvedDepartmentId: string | null;
}

// --- Workflow Escalation Settings ---

export interface WorkflowEscalationSettings {
	scanIntervalMinutes: number;
	lastScanAt: string | null;
	updatedAt: string;
	validIntervals: Array<{ minutes: number; label: string }>;
}

// --- Notification Settings ---

export interface IntervalOption {
	hours: number;
	label: string;
}

export interface NotificationSettings {
	defaultScanIntervalHours: number;
	minScanIntervalHours: number;
	validIntervals: IntervalOption[];
	updatedAt: string;
}

export interface UpdateNotificationSettingsRequest {
	defaultScanIntervalHours: number;
}

export interface DepartmentScanSettings {
	departmentId: string;
	departmentName: string;
	scanIntervalHours: number | null;
	effectiveIntervalHours: number;
	inheritsDefault: boolean;
	lastScanAt: string | null;
	validIntervals: IntervalOption[];
}

export interface UpdateDepartmentScanSettingsRequest {
	scanIntervalHours: number | null;
}

// --- AI Config ---

export interface AiConfig {
	llmProvider: string;
	llmEndpoint: string;
	llmModel: string;
	llmApiKey?: string | null;
	llmNumCtx: number;
	summarizationEnabled: boolean;
	keywordExtractionEnabled: boolean;
	classificationEnabled: boolean;
	relationshipDetectionEnabled: boolean;
	ocrEnabled: boolean;
}

export interface AiStats {
	totalProcessed: number;
	totalPending: number;
	averageConfidence: number;
	lastProcessedAt: string | null;
}

// --- Audit ---

export interface AuditEvent {
	id: string;
	eventType: string;
	aggregateType: string;
	aggregateId: string;
	actorId: string | null;
	departmentId: string | null;
	payload: Record<string, unknown>;
	occurredAt: string;
}
