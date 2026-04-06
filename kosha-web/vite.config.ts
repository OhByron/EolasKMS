import tailwindcss from '@tailwindcss/vite';
import { sveltekit } from '@sveltejs/kit/vite';
import { paraglide } from '@inlang/paraglide-sveltekit/vite';
import { defineConfig } from 'vite';

export default defineConfig({
	plugins: [
		tailwindcss(),
		paraglide({
			project: './project.inlang',
			outdir: './src/paraglide',
		}),
		sveltekit(),
	],
	server: {
		proxy: {
			'/api': {
				target: 'http://localhost:8081',
				changeOrigin: true,
			}
		}
	}
});
