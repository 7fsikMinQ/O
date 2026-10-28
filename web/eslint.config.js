import js from '@eslint/js';
import { defineConfig, globalIgnores } from 'eslint/config';
import tseslint from 'typescript-eslint';

export default defineConfig([
  globalIgnores(['dist', 'src/api/generated']),
  js.configs.recommended,
  tseslint.configs.recommended,
]);
