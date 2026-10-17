import esbuild from 'esbuild';
import { polyfillNode } from 'esbuild-plugin-polyfill-node';
import { createRequire } from 'module';
const require = createRequire(import.meta.url);
const assertPlugin = {
  name: 'assert-npm',
  setup(b) { b.onResolve({ filter: /^(node:)?assert$/ }, () => ({ path: require.resolve('assert/') })); },
};
const wasmPlugin = {
  name: 'no-node-wasm',
  setup(b) {
    // Moduły wasm-pack celują w Node (fs.readFileSync). Silnik ma wbudowany tryb zapasowy w czystym JS.
    b.onResolve({ filter: /^@railgun-community\/(poseidon-hash-wasm|curve25519-scalarmult-wasm)$/ },
      () => ({ path: require.resolve('./shims/empty.js') }));
  },
};
await esbuild.build({
  entryPoints: ['src/lab.js'], bundle: true, outfile: 'dist/lab.js', platform: 'browser', format: 'iife',
  target: ['chrome100'], minify: process.env.MIN === '1', logLevel: 'warning',
  define: { global: 'globalThis', 'process.env.NODE_ENV': '"production"' },
  plugins: [assertPlugin, wasmPlugin, polyfillNode()],
});
console.log('bundle ok');
