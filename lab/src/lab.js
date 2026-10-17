import {
  startRailgunEngine, loadProvider, createRailgunWallet, loadWalletByID, getProver, refreshBalances,
  setOnBalanceUpdateCallback, setOnUTXOMerkletreeScanCallback, setOnTXIDMerkletreeScanCallback, ArtifactStore,
  populateShieldBaseToken, gasEstimateForShieldBaseToken, getShieldPrivateKeySignatureMessage,
  generateUnshieldBaseTokenProof, populateProvedUnshieldBaseToken, gasEstimateForUnprovenUnshieldBaseToken,
} from '@railgun-community/wallet';
import { NetworkName, NETWORK_CONFIG, TXIDVersion, EVMGasType } from '@railgun-community/shared-models';
import LevelDB from 'level-js';
import localforage from 'localforage';
import { groth16 } from 'snarkjs';
import { Mnemonic, randomBytes, hexlify, HDNodeWallet, JsonRpcProvider, keccak256, parseEther, formatEther } from 'ethers';

const NET = NetworkName.EthereumSepolia;
const RPC = 'https://ethereum-sepolia-rpc.publicnode.com';
const POI_NODES = ['https://ppoi.fdi.network']; // publiczny węzeł agregatora POI z dokumentacji Railgun
const $ = (id) => document.getElementById(id);
const js = (o) => JSON.stringify(o, (k, v) => (typeof v === 'bigint' ? v.toString() : v));
const TXID = TXIDVersion.V2_PoseidonMerkle;
const WETH = NETWORK_CONFIG[NET].baseToken.wrappedAddress;
let ready = false, walletId = null, rgAddr = null, provider = null, pub = null;
const secs = (t0) => ((performance.now() - t0) / 1000).toFixed(1);

function getPub() {
  if (pub) return pub;
  const mn = localStorage.getItem('lab_mn');
  if (!mn) throw new Error('Najpierw utwórz portfel (krok 2)');
  provider = new JsonRpcProvider(RPC, 11155111);
  pub = HDNodeWallet.fromPhrase(mn, undefined, "m/44'/60'/0'/0/0").connect(provider);
  return pub;
}
async function showPublic() {
  const w = getPub();
  const bal = await provider.getBalance(w.address);
  log('Adres publiczny Sepolia (0x): ' + w.address + '\nSaldo: ' + formatEther(bal) + ' ETH (potrzebny faucet Sepolii, np. 0.05 ETH)');
}

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
  await startRailgunEngine('vaultlab', new LevelDB('railgun-engine-db'), false, store, false, false, POI_NODES);
  log('Silnik wystartował');
  const cfg = { chainId: 11155111, providers: [{ provider: RPC, priority: 1, weight: 2 }] };
  const res = await loadProvider(cfg, NET, 15000);
  log('Provider załadowany: ' + js(res));
  getProver().setSnarkJSGroth16(groth16);
  setOnUTXOMerkletreeScanCallback((d) => log('Skan UTXO: ' + js(d)));
  setOnTXIDMerkletreeScanCallback((d) => log('Skan TXID: ' + js(d)));
  setOnBalanceUpdateCallback((b) => {
    const parts = (b.erc20Amounts || []).map((a) => a.tokenAddress.slice(0, 8) + '…=' + formatEther(a.amount));
    if (parts.length) log('Saldo prywatne [' + b.balanceBucket + ']: ' + parts.join(', '));
  });
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
    log('Wczytano portfel. Adres 0zk: ' + info.railgunAddress);
    rgAddr = info.railgunAddress;
    return showPublic();
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
  rgAddr = info.railgunAddress;
  await showPublic();
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
async function shield() {
  if (!ready || !walletId || !rgAddr) return log('Najpierw kroki 1 i 2');
  const w = getPub();
  const amount = parseEther($('amt').value || '0.01');
  const bal = await provider.getBalance(w.address);
  if (bal < amount) return log('Za mało Sepolia ETH na ' + w.address + ' (saldo ' + formatEther(bal) + ')');
  log('Wpłata do puli Railgun: ' + formatEther(amount) + ' ETH…');
  const shieldPrivateKey = keccak256(await w.signMessage(getShieldPrivateKeySignatureMessage()));
  const wrapped = { tokenAddress: WETH, amount };
  const est = await gasEstimateForShieldBaseToken(TXID, NET, rgAddr, shieldPrivateKey, wrapped, w.address);
  const fee = await provider.getFeeData();
  const gasDetails = { evmGasType: EVMGasType.Type2, gasEstimate: est.gasEstimate,
    maxFeePerGas: fee.maxFeePerGas, maxPriorityFeePerGas: fee.maxPriorityFeePerGas };
  const { transaction } = await populateShieldBaseToken(TXID, NET, rgAddr, shieldPrivateKey, wrapped, gasDetails);
  transaction.from = w.address;
  const sent = await w.sendTransaction(transaction);
  log('Wysłano tx: ' + sent.hash + ' - czekam na potwierdzenie…');
  await sent.wait();
  log('Potwierdzone. Skanuj salda (krok 3). Środki staną się wydawalne po weryfikacji POI (status ShieldPending → Spendable).');
}

async function unshield() {
  if (!ready || !walletId) return log('Najpierw kroki 1 i 2');
  const w = getPub();
  const key = localStorage.getItem('lab_key');
  const amount = parseEther($('amt').value || '0.005');
  const wrapped = { tokenAddress: WETH, amount };
  const fee = await provider.getFeeData();
  const gd0 = { evmGasType: EVMGasType.Type2, gasEstimate: 0n,
    maxFeePerGas: fee.maxFeePerGas, maxPriorityFeePerGas: fee.maxPriorityFeePerGas };
  log('Wypłata ' + formatEther(amount) + ' ETH z puli na ' + w.address + ' - szacowanie gazu…');
  let t = performance.now();
  const est = await gasEstimateForUnprovenUnshieldBaseToken(TXID, NET, w.address, walletId, key, wrapped, gd0, undefined, true);
  log('Szacowanie gotowe w ' + secs(t) + ' s' + mem());
  t = performance.now();
  let last = -1;
  await generateUnshieldBaseTokenProof(TXID, NET, w.address, walletId, key, wrapped, undefined, true, undefined, (p) => {
    const pc = Math.floor(p * 10); if (pc !== last) { last = pc; log('Dowód: ' + Math.round(p * 100) + '%'); }
  });
  log('DOWÓD ZERO-KNOWLEDGE wygenerowany w ' + secs(t) + ' s' + mem());
  const gd = { ...gd0, gasEstimate: est.gasEstimate };
  const { transaction } = await populateProvedUnshieldBaseToken(TXID, NET, w.address, walletId, wrapped, undefined, true, undefined, gd);
  transaction.from = w.address;
  const sent = await w.sendTransaction(transaction);
  log('Wysłano tx: ' + sent.hash + ' - czekam na potwierdzenie…');
  await sent.wait();
  log('Wypłata potwierdzona. Sprawdź saldo 0x w kroku 2.');
}

function copyLog() {
  const text = $('log').textContent;
  const done = () => log('Skopiowano dziennik do schowka');
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(done).catch(() => fallbackCopy(text, done));
  } else fallbackCopy(text, done);
}
function fallbackCopy(text, done) {
  const t = document.createElement('textarea');
  t.value = text; document.body.appendChild(t); t.select();
  try { document.execCommand('copy'); done(); } catch (e) { log('Nie udało się skopiować: ' + e.message); }
  document.body.removeChild(t);
}
window.addEventListener('DOMContentLoaded', () => {
  $('b4').onclick = copyLog;
  $('b5').onclick = wrap(shield);
  $('b6').onclick = wrap(unshield);
  $('b1').onclick = wrap(init);
  $('b2').onclick = wrap(makeWallet);
  $('b3').onclick = wrap(scan);
  log('Laboratorium Railgun gotowe. Kroki 1 → 2 → 3.');
});
window.addEventListener('unhandledrejection', (e) => log('BŁĄD (async): ' + (e.reason && e.reason.message ? e.reason.message : e.reason)));
