#!/usr/bin/env node
/**
 * VidNest backend probe — mints a nonce, calls one provider path, and decrypts the v2 envelope.
 *
 * Measured facts this encodes (research/providers/vidnest_surface.md):
 *  - `_n` is 16 random bytes, base64url, unsigned → any client can mint it.
 *  - responses are {"d","n","v":2}: key = SHA-256(n + "|" + KEY), AES-256-GCM, iv = d[0..12).
 *
 * usage: node vidnest_probe.mjs /ophim/tv/94997/1/1
 *        node vidnest_probe.mjs /animehub/154587/1/hindi
 */
import crypto from "node:crypto";

const BASE = "https://new.vidnest.fun";
const KEY = "K3p8vQ2mX9rL5wZ0fN7bG1sH4tJ6dC9_y8S3wM1nQ5pL2";
const UA =
  "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/156.0.0.0 Safari/537.36";

const nonce = () => crypto.randomBytes(16).toString("base64url");

function decrypt(envelope) {
  if (!envelope || envelope.v !== 2 || typeof envelope.d !== "string") return envelope;
  const raw = Buffer.from(envelope.d, "base64url");
  const iv = raw.subarray(0, 12);
  const body = raw.subarray(12);
  const key = crypto.createHash("sha256").update(`${envelope.n}|${KEY}`).digest();
  const decipher = crypto.createDecipheriv("aes-256-gcm", key, iv);
  decipher.setAuthTag(body.subarray(body.length - 16));
  const plain = Buffer.concat([
    decipher.update(body.subarray(0, body.length - 16)),
    decipher.final(),
  ]);
  return JSON.parse(plain.toString("utf8"));
}

const path = process.argv[2] ?? "/ophim/tv/94997/1/1";
const url = `${BASE}${path}${path.includes("?") ? "&" : "?"}_n=${nonce()}`;
const res = await fetch(url, {
  headers: { "user-agent": UA, "accept-language": "en-US,en;q=0.6", accept: "*/*" },
});

const text = await res.text();
const headers = Object.fromEntries(
  [...res.headers].filter(([k]) => k.startsWith("x-") || k === "content-type"),
);

console.log("status", res.status, "bytes", text.length);
console.log("headers", headers);

let payload;
try {
  payload = JSON.parse(text);
} catch {
  console.log("not json:", text.slice(0, 200));
  process.exit(0);
}

const out = payload.v === 2 ? decrypt(payload) : payload;
console.log("decrypted:", JSON.stringify(out, null, 2).slice(0, 1200));
