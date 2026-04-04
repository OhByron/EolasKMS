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
	createdAt: string;
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

// --- AI Config ---

export interface AiConfig {
	llmProvider: string;
	llmEndpoint: string;
	llmModel: string;
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
