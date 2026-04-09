<script lang="ts">
	import * as m from '$paraglide/messages';
</script>

<svelte:head>
	<title>{m.runbook_title()} - {m.nav_app_title()}</title>
</svelte:head>

<div class="space-y-6">
	<div>
		<h1 class="text-2xl font-bold">{m.runbook_title()}</h1>
		<p class="mt-1 text-sm text-muted-foreground">{m.runbook_desc()}</p>
	</div>

	<section class="rounded-lg border border-border bg-card p-6">
		<h2 class="text-lg font-semibold">{m.runbook_when_title()}</h2>
		<ul class="mt-3 list-disc space-y-1 pl-5 text-sm text-muted-foreground">
			<li>{m.runbook_when_1()}</li>
			<li>{m.runbook_when_2()}</li>
			<li>{m.runbook_when_3()}</li>
		</ul>
	</section>

	<section class="rounded-lg border border-border bg-card p-6">
		<h2 class="text-lg font-semibold">{m.runbook_prereq_title()}</h2>
		<ol class="mt-3 list-decimal space-y-1 pl-5 text-sm text-muted-foreground">
			<li>{m.runbook_prereq_1()} (<code class="rounded bg-muted px-1 text-xs">curl -sf /actuator/health/liveness</code>)</li>
			<li>{m.runbook_prereq_2()}</li>
			<li>{m.runbook_prereq_3()}</li>
		</ol>
	</section>

	<section class="rounded-lg border border-border bg-card p-6">
		<h2 class="text-lg font-semibold">{m.runbook_csv_title()}</h2>
		<p class="mt-2 text-sm text-muted-foreground">{m.runbook_csv_headers()}</p>
		<div class="mt-4 overflow-x-auto">
			<table class="w-full text-sm">
				<thead>
					<tr class="border-b border-border text-left">
						<th class="pb-2 pr-4 font-medium">{m.runbook_col_column()}</th>
						<th class="pb-2 pr-4 font-medium">{m.runbook_col_required()}</th>
						<th class="pb-2 font-medium">{m.runbook_col_notes()}</th>
					</tr>
				</thead>
				<tbody class="text-muted-foreground">
					<tr class="border-b border-border/50">
						<td class="py-2 pr-4"><code class="text-xs">file_path</code></td>
						<td class="py-2 pr-4">Yes</td>
						<td class="py-2">Path to the file, relative to the root directory</td>
					</tr>
					<tr class="border-b border-border/50">
						<td class="py-2 pr-4"><code class="text-xs">title</code></td>
						<td class="py-2 pr-4">Yes</td>
						<td class="py-2">Document title as it will appear in Eòlas</td>
					</tr>
					<tr class="border-b border-border/50">
						<td class="py-2 pr-4"><code class="text-xs">description</code></td>
						<td class="py-2 pr-4">No</td>
						<td class="py-2">Free text; blank allowed</td>
					</tr>
					<tr class="border-b border-border/50">
						<td class="py-2 pr-4"><code class="text-xs">department_name</code></td>
						<td class="py-2 pr-4">Yes</td>
						<td class="py-2">Exact match against department name (case-insensitive)</td>
					</tr>
					<tr class="border-b border-border/50">
						<td class="py-2 pr-4"><code class="text-xs">category_name</code></td>
						<td class="py-2 pr-4">No</td>
						<td class="py-2">Exact match against document category name; blank means no category</td>
					</tr>
					<tr class="border-b border-border/50">
						<td class="py-2 pr-4"><code class="text-xs">owner_email</code></td>
						<td class="py-2 pr-4">Yes</td>
						<td class="py-2">Exact match against user email; unknown = row fails</td>
					</tr>
					<tr class="border-b border-border/50">
						<td class="py-2 pr-4"><code class="text-xs">tags</code></td>
						<td class="py-2 pr-4">No</td>
						<td class="py-2">Semicolon-separated taxonomy term labels; unknown = row fails</td>
					</tr>
					<tr class="border-b border-border/50">
						<td class="py-2 pr-4"><code class="text-xs">requires_legal_review</code></td>
						<td class="py-2 pr-4">No</td>
						<td class="py-2"><code class="text-xs">true</code>/<code class="text-xs">false</code>; blank = false</td>
					</tr>
					<tr>
						<td class="py-2 pr-4"><code class="text-xs">legal_reviewer_email</code></td>
						<td class="py-2 pr-4">Cond.</td>
						<td class="py-2">Required when <code class="text-xs">requires_legal_review=true</code></td>
					</tr>
				</tbody>
			</table>
		</div>
	</section>

	<section class="rounded-lg border border-border bg-card p-6">
		<h2 class="text-lg font-semibold">{m.runbook_example_title()}</h2>
		<pre class="mt-3 overflow-x-auto rounded bg-muted p-4 text-xs leading-relaxed">file_path,title,description,department_name,category_name,owner_email,tags,requires_legal_review,legal_reviewer_email
./policies/travel.pdf,"Travel Expense Policy","Company travel reimbursement policy","Finance","Policy","maria@example.com","travel;expense;policy",false,
./contracts/acme-msa.docx,"Acme MSA","Master services agreement","Legal","Contract","anne@example.com","contract;msa",true,"legal-reviewer@example.com"
./forms/onboarding.docx,"New Hire Onboarding","HR onboarding checklist","Human Resources","Form","frank@example.com","hr;onboarding",false,</pre>
	</section>

	<section class="rounded-lg border border-border bg-card p-6">
		<h2 class="text-lg font-semibold">{m.runbook_run_title()}</h2>
		<p class="mt-2 text-sm text-muted-foreground">{m.runbook_run_desc()}</p>
		<pre class="mt-3 overflow-x-auto rounded bg-muted p-4 text-xs leading-relaxed"># Build
./gradlew :kosha-import:bootJar

# Dry-run (validate without writing)
java -jar kosha-import/build/libs/kosha-import.jar \
  --csv ./manifest.csv \
  --root ./documents \
  --api-url http://localhost:8080 \
  --token "$KOSHA_ADMIN_TOKEN" \
  --dry-run

# Real run
java -jar kosha-import/build/libs/kosha-import.jar \
  --csv ./manifest.csv \
  --root ./documents \
  --api-url http://localhost:8080 \
  --token "$KOSHA_ADMIN_TOKEN"</pre>
	</section>

	<section class="rounded-lg border border-border bg-card p-6">
		<h2 class="text-lg font-semibold">{m.runbook_token_title()}</h2>
		<pre class="mt-3 overflow-x-auto rounded bg-muted p-4 text-xs leading-relaxed">KOSHA_ADMIN_TOKEN=$(curl -s -X POST \
  "http://localhost:8180/realms/kosha/protocol/openid-connect/token" \
  -d grant_type=password \
  -d client_id=kosha-web \
  -d username=admin@kosha.dev \
  -d password=admin | jq -r .access_token)</pre>
	</section>

	<section class="rounded-lg border border-border bg-card p-6">
		<h2 class="text-lg font-semibold">{m.runbook_dryrun_title()}</h2>
		<ol class="mt-3 list-decimal space-y-1 pl-5 text-sm text-muted-foreground">
			<li>Parses every row and reports syntactic errors</li>
			<li>Resolves every <code class="text-xs">department_name</code> against departments</li>
			<li>Resolves every <code class="text-xs">category_name</code> against document categories</li>
			<li>Resolves every <code class="text-xs">owner_email</code> against user profiles</li>
			<li>Resolves every <code class="text-xs">legal_reviewer_email</code> against legal review departments</li>
			<li>Resolves every tag in <code class="text-xs">tags</code> against taxonomy terms</li>
			<li>Returns a per-row ok/error verdict</li>
		</ol>
		<p class="mt-3 text-sm text-muted-foreground">No files are touched during dry-run. After a green dry-run, re-run without <code class="text-xs">--dry-run</code> to execute.</p>
	</section>

	<section class="rounded-lg border border-border bg-card p-6">
		<h2 class="text-lg font-semibold">{m.runbook_resume_title()}</h2>
		<p class="mt-2 text-sm text-muted-foreground">{m.runbook_resume_desc()}</p>
		<ul class="mt-3 list-disc space-y-1 pl-5 text-sm text-muted-foreground">
			<li>{m.runbook_resume_1()}</li>
			<li>{m.runbook_resume_2()}</li>
			<li>{m.runbook_resume_3()}</li>
		</ul>
	</section>

	<section class="rounded-lg border border-border bg-card p-6">
		<h2 class="text-lg font-semibold">{m.runbook_error_title()}</h2>
		<ul class="mt-3 list-disc space-y-1 pl-5 text-sm text-muted-foreground">
			<li><strong>Validation error</strong> (exit 2): per-row error summary, nothing written</li>
			<li><strong>Row error during real run</strong> (e.g. file missing, API 4xx): row marked failed, CLI continues. Summary reports succeeded/failed counts.</li>
			<li><strong>Network error</strong> (exit 3): CLI aborts. Re-run resumes from last completed row.</li>
		</ul>
	</section>

	<section class="rounded-lg border border-border bg-card p-6">
		<h2 class="text-lg font-semibold">{m.runbook_limits_title()}</h2>
		<ul class="mt-3 list-disc space-y-1 pl-5 text-sm text-muted-foreground">
			<li>{m.runbook_limits_1()}</li>
			<li>{m.runbook_limits_2()}</li>
			<li>{m.runbook_limits_3()}</li>
			<li>{m.runbook_limits_4()}</li>
		</ul>
	</section>

	<div class="pb-4">
		<a href="/admin/import" class="text-sm text-primary underline hover:opacity-80">{m.runbook_back_link()}</a>
	</div>
</div>
