import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import { seal, ALG, INFO } from '../agent/seal.mjs';
import { buildXrayConfig, STATUS_HOST } from '../agent/xray-config.mjs';
import { buildExitXrayConfig, exitSlots, parseVpngate } from '../agent/exits.mjs';

function open(envelope, pair) {
  const eph = crypto.createPublicKey({key: {kty:'OKP', crv:'X25519', x:envelope.epk}, format:'jwk'});
  const shared = crypto.diffieHellman({privateKey: pair.privateKey, publicKey: eph});
  const pub = pair.publicKey.export({format:'jwk'}).x;
  const salt = Buffer.concat([Buffer.from(envelope.epk,'base64url'), Buffer.from(pub,'base64url')]);
  const key = crypto.hkdfSync('sha256', shared, salt, Buffer.from(INFO), 32);
  const body = Buffer.from(envelope.ct, 'base64url');
  const d = crypto.createDecipheriv('aes-256-gcm', key, Buffer.from(envelope.iv, 'base64url'));
  d.setAAD(Buffer.from(`${INFO}|${envelope.sid}`)); d.setAuthTag(body.subarray(-16));
  return JSON.parse(Buffer.concat([d.update(body.subarray(0,-16)), d.final()]));
}

test('session envelope is encrypted, bound to its session, and rejects tampering', () => {
  const pair = crypto.generateKeyPairSync('x25519');
  const key = pair.publicKey.export({format:'jwk'}).x;
  const envelope = seal(key, 'test_session', {uuid:'private', phase:'ready'});
  assert.equal(envelope.alg, ALG);
  assert.equal(JSON.stringify(envelope).includes('private'), false);
  assert.deepEqual(open(envelope, pair), {uuid:'private', phase:'ready'});
  assert.throws(() => open({...envelope, sid:'other_session'}, pair));
  const ct = Buffer.from(envelope.ct, 'base64url'); ct[0] ^= 1;
  assert.throws(() => open({...envelope, ct:ct.toString('base64url')}, pair));
});

test('server routes status first and selected users to their own exit slots', () => {
  const slots = exitSlots(['a','b']);
  const cfg = buildXrayConfig({uuid:'direct',wsPath:'/abc',exits:slots});
  assert.equal(cfg.inbounds[0].listen, '127.0.0.1');
  assert.deepEqual(cfg.routing.rules[0].domain, [`full:${STATUS_HOST}`]);
  assert.equal(cfg.routing.rules.find(r => r.user?.includes('x1@gt')).outboundTag, 'exit-x1');
  assert.equal(cfg.outbounds.find(x => x.tag === 'exit-x2').settings.servers[0].port, 21102);
  assert(cfg.routing.rules.some(r => r.ip?.includes('geoip:private')));
});

test('new Android sessions opt into VMess without changing legacy VLESS sessions', () => {
  const legacy = buildXrayConfig({uuid:'direct', wsPath:'/abc'});
  const mobile = buildXrayConfig({uuid:'direct', wsPath:'/abc', protocol:'vmess'});
  assert.equal(legacy.inbounds[0].protocol, 'vless');
  assert.equal(legacy.inbounds[0].settings.decryption, 'none');
  assert.equal(mobile.inbounds[0].protocol, 'vmess');
  assert.equal(mobile.inbounds[0].settings.decryption, undefined);
  assert.throws(() => buildXrayConfig({uuid:'x', wsPath:'/abc', protocol:'unknown'}));
});

test('provider settings do not quietly treat Psiphon as UDP-capable', () => {
  const ps = buildExitXrayConfig({port:21101, provider:'psiphon',psiphonPort:21201});
  assert.equal(ps.routing.rules.find(r => r.network === 'udp' && !r.port).outboundTag, 'udp-direct');
  const vpn = buildExitXrayConfig({port:21101,provider:'vpngate',dev:'tun11'});
  assert.equal(vpn.outbounds[0].streamSettings.sockopt.interface, 'tun11');
  assert.throws(() => buildExitXrayConfig({port:1,provider:'vpngate',dev:'eth0'}));
  assert.deepEqual(parseVpngate('bad,line\n'), {});
});
