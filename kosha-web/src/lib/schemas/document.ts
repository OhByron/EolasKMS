import { z } from 'zod';

export const createDocumentSchema = z.object({
	title: z.string().min(1, 'Title is required').max(500),
	description: z.string().max(5000).optional(),
	departmentId: z.string().uuid('Select a department'),
	categoryId: z.string().uuid().optional(),
	storageMode: z.enum(['VAULT', 'CONNECTOR']).default('VAULT'),
	workflowType: z.enum(['NONE', 'LINEAR', 'PARALLEL']).default('NONE')
});

export const updateDocumentSchema = z.object({
	title: z.string().min(1).max(500).optional(),
	description: z.string().max(5000).optional(),
	categoryId: z.string().uuid().optional(),
	status: z.string().optional(),
	workflowType: z.enum(['NONE', 'LINEAR', 'PARALLEL']).optional()
});

export const createVersionSchema = z.object({
	fileName: z.string().min(1, 'File name is required'),
	fileSizeBytes: z.number().positive().optional(),
	storageKey: z.string().optional(),
	changeSummary: z.string().max(2000).optional()
});

export type CreateDocumentInput = z.infer<typeof createDocumentSchema>;
export type UpdateDocumentInput = z.infer<typeof updateDocumentSchema>;
export type CreateVersionInput = z.infer<typeof createVersionSchema>;
