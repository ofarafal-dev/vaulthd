import {
  startRailgunEngine, loadProvider, createRailgunWallet, loadWalletByID, getProver, refreshBalances,
  setOnBalanceUpdateCallback, setOnUTXOMerkletreeScanCallback, setOnTXIDMerkletreeScanCallback, ArtifactStore,
} from '@railgun-community/wallet';
import { NetworkName, NETWORK_CONFIG } from '@railgun-community/shared-models';
import LevelDB from 'level-js';
import localforage from 'localforage';
import { groth16 } from 'snarkjs';
import { Mnemonic, randomBytes, hexlify } from 'ethers';

const NET = NetworkName.EthereumSepolia;
const RPC = 'https://ethereum-sepolia-rpc.publicnode.com';
const $ = (id) => document.getElementById(id);
const js = (o) => JSON.stringify(o, (k, v) => (typeof v === 'bigint' ? v.toString() : v));
let ready = false, walletId = null;

function log(m) {
  const t = new Date().toISOString().slice(11, 19);
  const el = $('log');
  el.textContent += `[${t}] ${m}\n`;
  el.scrollTop = el.scrollHeight;
}
function mem() {
  const m = performance && performance.memory;
  return m ? ` (pamięć JS: ${(m.usedJSHeapSize / 1048576).toFixed(0)} MB)` : '';
}
async function rpcBlock() {
  const r = await fetch(RPC, { method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'eth_blockNumber', params: [] }) });
  return parseInt((await r.json()).result, 16);
}

async function init() {
  if (ready) return log('Silnik już działa');
  log('Start silnika Railgun (Sepolia)…');
  const t0 = performance.now();
  localforage.config({ name: 'railgun-artifacts' });
  const store = new ArtifactStore(
    async (p) => localforage.getItem(p),
    async (dir, p, item) => { await localforage.setItem(p, item); },
    async (p) => (await localforage.getItem(p)) != null);
  await startRailgunEngine('vaultlab', new LevelDB('railgun-engine-db'), false, store, false, false);
  log('Silnik wystartował');
  const cfg = { chainId: 11155111, providers: [{ provider: RPC, priority: 1, weight: 2 }] };
  const res = await loadProvider(cfg, NET, 15000);
  log('Provider załadowany: ' + js(res));
  getProver().setSnarkJSGroth16(groth16);
  setOnUTXOMerkletreeScanCallback((d) => log('Skan UTXO: ' + js(d)));
  setOnTXIDMerkletreeScanCallback((d) => log('Skan TXID: ' + js(d)));
  setOnBalanceUpdateCallback((b) => log('Aktualizacja sald: ' + js(b)));
  ready = true;
  log(`Gotowe w ${((performance.now() - t0) / 1000).toFixed(1)} s${mem()}`);
}

async function makeWallet() {
  if (!ready) return log('Najpierw uruchom silnik');
  let key = localStorage.getItem('lab_key');
  if (!key) { key = hexlify(randomBytes(32)).slice(2); localStorage.setItem('lab_key', key); }
  const wid = localStorage.getItem('lab_wid');
  if (wid) {
    const info = await loadWalletByID(key, wid, false);
    walletId = info.id;
    return log('Wczytano portfel. Adres 0zk: ' + info.railgunAddress);
  }
  let mn = localStorage.getItem('lab_mn');
  if (!mn) {
    mn = Mnemonic.fromEntropy(randomBytes(16)).phrase;
    localStorage.setItem('lab_mn', mn);
    log('Wygenerowano seed laboratorium (tylko testnet): ' + mn);
  }
  const block = await rpcBlock();
  const info = await createRailgunWallet(key, mn, { [NET]: block });
  walletId = info.id;
  localStorage.setItem('lab_wid', info.id);
  log('Utworzono portfel od bloku ' + block + '. Adres 0zk: ' + info.railgunAddress);
}

async function scan() {
  if (!walletId) return log('Najpierw utwórz portfel');
  const chain = NETWORK_CONFIG[NET].chain;
  log('Skanowanie sald…');
  const t0 = performance.now();
  await refreshBalances(chain, [walletId]);
  log(`Skan zakończony w ${((performance.now() - t0) / 1000).toFixed(1)} s${mem()}`);
}

function wrap(fn) { return async () => { try { await fn(); } catch (e) { log('BŁĄD: ' + (e && e.message ? e.message : e)); } }; }
window.addEventListener('DOMContentLoaded', () => {
  $('b1').onclick = wrap(init);
  $('b2').onclick = wrap(makeWallet);
  $('b3').onclick = wrap(scan);
  log('Laboratorium Railgun gotowe. Kroki 1 → 2 → 3.');
});
window.addEventListener('unhandledrejection', (e) => log('BŁĄD (async): ' + (e.reason && e.reason.message ? e.reason.message : e.reason)));
