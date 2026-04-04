import { z } from 'zod';

export const createDepartmentSchema = z.object({
	name: z.string().min(1, 'Name is required').max(200),
	description: z.string().max(2000).optional(),
	parentDeptId: z.string().uuid().optional()
});

export const updateDepartmentSchema = z.object({
	name: z.string().min(1).max(200).optional(),
	description: z.string().max(2000).optional(),
	managerUserId: z.string().uuid().optional(),
	parentDeptId: z.string().uuid().optional(),
	status: z.enum(['ACTIVE', 'INACTIVE']).optional()
});

export type CreateDepartmentInput = z.infer<typeof createDepartmentSchema>;
export type UpdateDepartmentInput = z.infer<typeof updateDepartmentSchema>;
