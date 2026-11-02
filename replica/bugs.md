# Bug Report Log — RF Field Suite (`v1.0.0`)

### BUG-001: Vitest default file discovery picked up `replica-skill/replica-test/e2e.example.spec.ts`
- **Severity:** `S3`
- **Flow / Case:** Test Runner Setup
- **Screen:** All
- **Steps:**
  1. Clone `replica-skill` into workspace root and copy skills into `.claude/skills/`.
  2. Run `npm test` (`vitest run`).
- **Expected:** Vitest executes only application test suites under `src/`.
- **Actual:** Vitest attempted to run the template file `replica-skill/replica-test/e2e.example.spec.ts`.
- **Evidence:** Initial RED test run output.
- **Status:** `fixed` in `vite.config.ts` (`test.include: ['src/**/*.test.{ts,tsx}']`).

No open `S1`, `S2`, `S3`, or `S4` bugs remain.

